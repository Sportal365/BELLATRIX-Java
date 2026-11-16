package solutions.bellatrix.web.infrastructure;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.lightbody.bmp.core.har.HarEntry;
import net.lightbody.bmp.core.har.HarRequest;
import net.lightbody.bmp.core.har.HarResponse;
import net.lightbody.bmp.core.har.HarPostData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.support.ui.FluentWait;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ProxyServerResponseTests {
    private Gson previousGson;

    @BeforeEach
    void setup() throws Exception {
        var field = ProxyServer.class.getDeclaredField("gson");
        field.setAccessible(true);
        previousGson = (Gson) field.get(null);
        field.set(null, new Gson());
        ProxyServer.newHar();
    }

    @AfterEach
    void cleanup() throws Exception {
        ProxyServer.clearHistory();
        var field = ProxyServer.class.getDeclaredField("gson");
        field.setAccessible(true);
        field.set(null, previousGson);
    }

    @Test
    void skipsCancelledAndBodylessRequestsWhileKeepingTheFirstAvailableResponse() {
        addResponse(0, null);
        addResponse(304, "");
        addResponse(200, "first");
        addResponse(200, "later");

        assertEquals("first", ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class).get("id").getAsString());
        Map<String, String> response = ProxyServer.getResponseByUrl("/record", "GET",
                new TypeToken<Map<String, String>>() {}.getType());
        assertEquals("first", response.get("id"));
        assertEquals(4, ProxyServer.getCapturedEntries().size());
    }

    @Test
    void preservesErrorResponsesAndRequestMethodFiltering() {
        addResponse(422, "error");
        addResponse(200, "success");

        assertEquals("error", ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class).get("id").getAsString());
        assertNull(ProxyServer.getResponseByUrl("/record", "POST", JsonObject.class));
    }

    @Test
    void returnsNoResponseWhileEveryMatchingBodyIsPending() {
        addResponse(0, null);

        assertNull(ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class));
        assertNull(ProxyServer.getResponseByUrl("/record", "GET", new TypeToken<Map<String, String>>() {}.getType()));
    }

    @Test
    void exactEndpointWaitExcludesOldResponsesSubresourcesAndOtherOrigins() {
        addResponse(200, "old");
        var checkpoint = ProxyServer.checkpoint();
        addResponse(200, "subresource").getRequest().setUrl("https://example.test/record/related");
        addResponse(200, "another-origin").getRequest().setUrl("https://other.test/record");
        addResponse(200, "other-method").getRequest().setMethod("POST");
        addResponse(200, "current").getRequest().setUrl("https://example.test/record?include=sources");
        assertEquals("current", ProxyServer.waitForResponseAtEndpoint("https://example.test/record", HttpMethod.GET,
                shortWait(), checkpoint, JsonObject.class).get("id").getAsString());
    }

    @Test
    void exactEndpointWaitCannotFallBackToHistoryOrASubresource() {
        addResponse(200, "old");
        var checkpoint = ProxyServer.checkpoint();
        addResponse(200, "subresource").getRequest().setUrl("https://example.test/record/sources");
        assertThrows(TimeoutException.class, () -> ProxyServer.waitForResponseAtEndpoint(
                "https://example.test/record", HttpMethod.GET, shortWait(), checkpoint, JsonObject.class));
    }

    @Test
    void checkpointReadsOnlyNewResponsesWithoutDiscardingConfigurationHistory() {
        addResponse(200, "old");
        var checkpoint = ProxyServer.checkpoint();
        addResponse(0, null);
        addResponse(200, "current");

        assertEquals("old", ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class).get("id").getAsString());
        assertEquals("current", ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class, checkpoint).get("id").getAsString());
        Map<String, String> response = ProxyServer.getResponseByUrl("/record", "GET",
                new TypeToken<Map<String, String>>() {}.getType(), checkpoint);
        assertEquals("current", response.get("id"));
        assertEquals(3, ProxyServer.getCapturedEntries().size());
    }

    @Test
    void checkpointDoesNotAcceptAnOlderRequestThatCompletesAfterTheAction() {
        var oldRequest = addResponse(0, null);
        var checkpoint = ProxyServer.checkpoint();
        oldRequest.getResponse().setStatus(200);
        oldRequest.getResponse().getContent().setText("{\"id\":\"old\"}");

        assertNull(ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class, checkpoint));
        var error = assertThrows(RuntimeException.class, () ->
                ProxyServer.waitForResponse("/record", HttpMethod.GET, shortWait(), checkpoint));
        assertInstanceOf(TimeoutException.class, error.getCause());
    }

    @Test
    void checkpointWaitsForTheNewResponseBody() {
        addResponse(200, "old");
        var checkpoint = ProxyServer.checkpoint();
        var pending = addResponse(200, null);

        assertThrows(RuntimeException.class, () ->
                ProxyServer.waitForResponse("/record", HttpMethod.GET, shortWait(), checkpoint));
        pending.getResponse().getContent().setText("{\"id\":\"current\"}");
        ProxyServer.waitForResponse("/record", HttpMethod.GET, shortWait(), checkpoint);
    }

    @Test
    void checkpointAcceptsBodylessDeletionButDoesNotReuseAnOldDeletion() {
        var old = addResponse(204, null);
        old.getRequest().setMethod("DELETE");
        var checkpoint = ProxyServer.checkpoint();
        assertThrows(RuntimeException.class, () ->
                ProxyServer.waitForResponse("/record", HttpMethod.DELETE, shortWait(), checkpoint));

        var current = addResponse(204, null);
        current.getRequest().setMethod("DELETE");
        ProxyServer.waitForResponse("/record", HttpMethod.DELETE, shortWait(), checkpoint);
    }

    @Test
    void checkpointKeepsValidationErrorsAndFiltersOtherEndpointsAndMethods() {
        var checkpoint = ProxyServer.checkpoint();
        addResponse(200, "wrong method").getRequest().setMethod("POST");
        addResponse(200, "wrong endpoint").getRequest().setUrl("https://example.test/other");
        addResponse(422, "validation error");

        ProxyServer.waitForResponse("/record", HttpMethod.GET, shortWait(), checkpoint);
        assertEquals("validation error", ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class, checkpoint)
                .get("id").getAsString());
    }

    @Test
    void skipsEntriesWhoseRequestOrResponseIsStillBeingInitialized() {
        var checkpoint = ProxyServer.checkpoint();
        addResponse(0, null).setRequest(null);
        addResponse(0, null).setResponse(null);
        addResponse(200, "current");

        ProxyServer.waitForResponse("/record", HttpMethod.GET, shortWait(), checkpoint);
        assertEquals("current", ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class, checkpoint)
                .get("id").getAsString());
    }

    @Test
    void checkpointAlsoScopesRequestPayloadAssertions() {
        var old = addResponse(200, "old");
        old.getRequest().setPostData(new HarPostData());
        old.getRequest().getPostData().setText("{\"id\":\"old\"}");
        var checkpoint = ProxyServer.checkpoint();
        var current = addResponse(200, "current");
        current.getRequest().setPostData(new HarPostData());
        current.getRequest().getPostData().setText("{\"id\":\"current\"}");

        assertEquals("current", ProxyServer.getRequestByUrl("/record", "GET", JsonObject.class, checkpoint)
                .get("id").getAsString());
        Map<String, String> request = ProxyServer.getRequestByUrl("/record", "GET",
                new TypeToken<Map<String, String>>() {}.getType(), checkpoint);
        assertEquals("current", request.get("id"));
    }

    @Test
    void checkpointFailsClearlyAfterCaptureIsReset() {
        var checkpoint = ProxyServer.checkpoint();
        ProxyServer.newHar();
        addResponse(200, "another capture");

        assertThrows(IllegalStateException.class, () ->
                ProxyServer.getResponseByUrl("/record", "GET", JsonObject.class, checkpoint));
        assertThrows(IllegalStateException.class, () ->
                ProxyServer.waitForResponse("/record", HttpMethod.GET, shortWait(), checkpoint));
    }

    private FluentWait<WebDriver> shortWait() {
        return new FluentWait<WebDriver>(null).withTimeout(Duration.ofMillis(30)).pollingEvery(Duration.ofMillis(1));
    }

    private HarEntry addResponse(int status, String id) {
        var entry = new HarEntry("test");
        entry.setRequest(new HarRequest("GET", "https://example.test/record", "HTTP/1.1"));
        entry.setResponse(new HarResponse(status, "", "HTTP/1.1"));
        entry.getResponse().getContent().setText(id == null || id.isEmpty() ? id : "{\"data\":{\"id\":\"" + id + "\"}}");
        ProxyServer.getCapturedEntries().add(entry);
        return entry;
    }
}
