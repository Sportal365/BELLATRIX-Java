package solutions.bellatrix.playwright.infrastructure.httptraffic;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.microsoft.playwright.BrowserContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class CaptureHistoryTests {
    private static final String ENDPOINT = "https://example.test/record";
    private final CaptureHistory<String, String> history = new CaptureHistory<>(32, 4096);

    @Test
    void findsFirstCompletedMatchingBodyAndKeepsRequestOrder() {
        start("pending", "GET", null);
        respond("pending", 200);
        start("empty", "GET", null);
        respond("empty", 200);
        finish("empty", "");
        start("wrong-method", "POST", null);
        respond("wrong-method", 200);
        finish("wrong-method", "{}");
        start("first", "GET", null);
        respond("first", 422);
        start("second", "GET", null);
        respond("second", 200);
        finish("second", "{\"id\":\"second\"}");
        assertEquals("{\"id\":\"second\"}", matchingResponse(null).responseBody());

        finish("first", "{\"id\":\"first\"}");

        assertEquals(422, matchingResponse(null).status());
        assertEquals("{\"id\":\"first\"}", matchingResponse(null).responseBody());
        assertEquals(5, history.snapshot(null).size());
    }

    @Test
    void excludesOldRequestsThatFinishAfterCheckpoint() {
        start("old", "GET", "{\"id\":\"old\"}");
        respond("old", 200);
        var checkpoint = history.checkpoint();
        finish("old", "{\"id\":\"old\"}");

        assertNull(matchingResponse(checkpoint));

        start("current", "GET", "{\"id\":\"current\"}");
        respond("current", 200);
        assertNull(matchingResponse(checkpoint));
        assertEquals("{\"id\":\"current\"}", history.find(checkpoint, RequestMatcher.forUrl(ENDPOINT, "GET"))
                .requestBody());
        finish("current", "{\"id\":\"current\"}");

        assertEquals("{\"id\":\"old\"}", matchingResponse(null).responseBody());
        assertEquals("{\"id\":\"current\"}", matchingResponse(checkpoint).responseBody());
    }

    @Test
    void resetInvalidatesCheckpointsAndDropsPreviousResponses() {
        start("old", "GET", null);
        var checkpoint = history.checkpoint();
        history.reset();
        respond("old", 200);
        assertNull(finish("old", "{}"));
        assertThrows(IllegalStateException.class, () -> matchingResponse(checkpoint));
        assertTrue(history.snapshot(null).isEmpty());

        start("fresh", "GET", null);
        respond("fresh", 200);
        finish("fresh", "{\"id\":\"fresh\"}");
        assertEquals("{\"id\":\"fresh\"}", matchingResponse(null).responseBody());
    }

    @Test
    void closingCaptureClearsRequestsAndResponsesAndRejectsReads() {
        start("request", "GET", "{}");
        respond("request", 200);
        finish("request", "{}");
        var checkpoint = history.checkpoint();

        history.close();
        start("late", "GET", null);

        assertNull(history.exchange("request"));
        assertNull(history.exchange("late"));
        assertThrows(IllegalStateException.class, () -> history.snapshot(checkpoint));
        assertThrows(IllegalStateException.class, () -> history.snapshot(null));
    }

    @Test
    void checkpointExpiresWhenEntryStartedAfterItIsEvicted() {
        var bounded = new CaptureHistory<String, String>(2, 4096);
        bounded.start("old", ENDPOINT, "GET", "fetch", Map.of(), null);
        var checkpoint = bounded.checkpoint();
        bounded.start("first", ENDPOINT, "GET", "fetch", Map.of(), null);
        bounded.start("second", ENDPOINT, "GET", "fetch", Map.of(), null);

        assertEquals(2, bounded.snapshot(checkpoint).size());

        bounded.start("third", ENDPOINT, "GET", "fetch", Map.of(), null);

        assertEquals(2, bounded.snapshot(null).size());
        assertNotNull(bounded.exchange("second"));
        assertNotNull(bounded.exchange("third"));
        assertThrows(IllegalStateException.class, () -> bounded.snapshot(checkpoint));
    }

    @Test
    void requestAndResponseBodiesShareOneBoundedHistory() {
        var bounded = new CaptureHistory<String, String>(32, 16);
        bounded.start("old", ENDPOINT, "POST", "fetch", Map.of(), "1234");
        bounded.start("current", ENDPOINT, "POST", "fetch", Map.of(), "5678");
        bounded.response("current", "response", 200, Map.of());

        bounded.finish("current", "abcd", 4, null, null);

        assertEquals(1, bounded.snapshot(null).size());
        assertEquals("5678", bounded.snapshot(null).get(0).requestBody());
        assertEquals("abcd", bounded.snapshot(null).get(0).responseBody());
        assertNull(bounded.finish("old", "late", 4, null, null));
    }

    @Test
    void endpointMatchingRequiresOriginPathMethodQueryAndResourceType() {
        history.start("wrong-scheme", "http://example.test/record?id=3", "GET", "fetch", Map.of(), null);
        history.start("wrong-host", "https://other.test/record?id=3", "GET", "fetch", Map.of(), null);
        history.start("wrong-port", "https://example.test:8443/record?id=3", "GET", "fetch", Map.of(), null);
        history.start("subroute", "https://example.test/record/related?id=3", "GET", "fetch", Map.of(), null);
        history.start("similar-path", "https://example.test/records?id=3", "GET", "fetch", Map.of(), null);
        history.start("wrong-method", "https://example.test/record?id=3", "POST", "fetch", Map.of(), null);
        history.start("asset", "https://example.test/record?id=3", "GET", "script", Map.of(), null);
        history.start("wrong-query", "https://example.test/record?id=31", "GET", "fetch", Map.of(), null);
        var matcher = RequestMatcher.forEndpoint("https://EXAMPLE.test:443/record?id=3", "get");

        assertNull(history.find(null, matcher));

        history.start("match", "https://example.test/record?id=3", "GET", "xhr", Map.of("x-project", "test"), null);

        assertEquals("test", history.find(null, matcher).requestHeaders().get("x-project"));
    }

    @Test
    void collectionLookupsMatchDescendantsAndAcceptAnIncompleteQueryValue() {
        String articles = "https://example.test/articles";
        history.start("similar", articles + "-other", "PATCH", "fetch", Map.of(), null);
        history.start("article", articles + "/123", "PATCH", "fetch", Map.of(), null);
        history.start("tags", "https://example.test/tags/search?query=some+words&limit=10",
                "GET", "xhr", Map.of(), null);

        assertEquals(articles + "/123", history.find(null, RequestMatcher.forUrl(articles, "PATCH")).url());
        assertNull(history.find(null, RequestMatcher.forEndpoint(articles, "PATCH")));
        assertNotNull(history.find(null, RequestMatcher.forUrl("https://example.test/tags/search?query=", "GET")));
        assertNotNull(history.find(null, RequestMatcher.forUrl("https://example.test/tags/search?query=some%20words", "GET")));
    }

    @Test
    void deserializesRequestPayloadAndResponseEnvelopeWithTypedModels() {
        String body = "{\"data\":{\"id\":\"record\"}}";
        JsonObject payload = CaptureJson.request(body, JsonObject.class);
        JsonObject response = CaptureJson.response(body, JsonObject.class);
        var type = new TypeToken<Map<String, String>>() { }.getType();
        Map<String, String> mapped = CaptureJson.response(body, type);

        assertNotNull(payload.getAsJsonObject("data"));
        assertEquals("record", response.get("id").getAsString());
        assertEquals(Map.of("id", "record"), mapped);

        Dates dates = CaptureJson.response("{\"data\":{\"local\":\"2026-11-12T10:00:00+00:00\","
                + "\"offset\":\"2026-11-12T10:00:00\"}}", Dates.class);
        assertEquals(LocalDateTime.parse("2026-11-12T10:00:00"), dates.local());
        assertEquals(OffsetDateTime.parse("2026-11-12T10:00:00Z"), dates.offset());
    }

    @Test
    void uninstallDetachesEveryCaptureListener() {
        var listeners = new HashMap<String, Object>();
        var context = (BrowserContext) Proxy.newProxyInstance(BrowserContext.class.getClassLoader(),
                new Class<?>[]{BrowserContext.class}, (object, method, arguments) -> {
                    String name = method.getName();
                    if (name.startsWith("on")) {
                        assertNull(listeners.put(name.substring(2), arguments[0]));
                    } else if (name.startsWith("off")) {
                        assertSame(listeners.remove(name.substring(3)), arguments[0]);
                    } else {
                        throw new UnsupportedOperationException(name);
                    }
                    return null;
                });

        Traffic.install(context);
        try {
            assertEquals(Set.of("Request", "Response", "RequestFinished", "RequestFailed"), listeners.keySet());
        } finally {
            Traffic.uninstall(context);
        }

        assertTrue(listeners.isEmpty());
        assertNull(Traffic.find(context));
    }

    private void start(String key, String method, String body) {
        history.start(key, ENDPOINT, method, "fetch", Map.of(), body);
    }

    private void respond(String key, int status) {
        history.response(key, key, status, Map.of());
    }

    private CapturedRequest finish(String key, String body) {
        return history.finish(key, body, body.length(), null, null);
    }

    private CapturedRequest matchingResponse(Traffic.Checkpoint checkpoint) {
        return history.find(checkpoint, RequestMatcher.forUrl(ENDPOINT, "GET").and(CapturedRequest::hasResponseBody));
    }

    private record Dates(LocalDateTime local, OffsetDateTime offset) {
    }
}
