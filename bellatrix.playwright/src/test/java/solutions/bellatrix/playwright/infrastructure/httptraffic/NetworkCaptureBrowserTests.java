package solutions.bellatrix.playwright.infrastructure.httptraffic;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import solutions.bellatrix.playwright.infrastructure.BrowserConfiguration;
import solutions.bellatrix.playwright.infrastructure.BrowserTypes;
import solutions.bellatrix.playwright.infrastructure.Lifecycle;
import solutions.bellatrix.playwright.infrastructure.PlaywrightService;
import solutions.bellatrix.playwright.services.NetworkService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("browser-contract")
class NetworkCaptureBrowserTests {
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int SCRIPT_COUNT = 9;
    private static final byte[] SDK_SCRIPT = ("/*" + "x".repeat(1_600_000) + "*/").getBytes(StandardCharsets.UTF_8);

    @AfterEach
    void closeBrowser() {
        PlaywrightService.close();
    }

    @Test
    void matchesTheCurrentRequestAndWaitsForItsDelayedBody() throws IOException {
        try (var server = new LocalServer()) {
            var page = open(server);
            page.evaluate("""
                    () => { window.oldResponse = fetch('/record', {method: 'POST', body: '{"id":"old"}'}); }
                    """);
            waitFor(page, () -> NetworkService.getCapturedEntries().stream().anyMatch(entry ->
                    entry.url().equals(server.endpoint()) && entry.status() != null && !entry.completed()));

            var checkpoint = NetworkService.checkpoint();
            server.releaseOldBody();
            page.evaluate("async () => (await window.oldResponse).json()");
            assertNull(NetworkService.getResponseByUrl(server.endpoint(), "POST", JsonObject.class, checkpoint));

            page.evaluate("""
                    () => { fetch('/record', {method: 'POST', headers: {'x-project': 'fixture'}, body: '{"id":"current"}'}); }
                    """);
            waitFor(page, () -> NetworkService.getCapturedEntries(checkpoint).stream().anyMatch(entry ->
                    entry.url().equals(server.endpoint()) && entry.status() != null && !entry.completed()));
            assertNull(NetworkService.getResponseByUrl(server.endpoint(), "POST", JsonObject.class, checkpoint));
            assertEquals("current", NetworkService.getRequestByUrl(server.endpoint(), "POST", JsonObject.class, checkpoint)
                    .get("id").getAsString());

            var response = NetworkService.waitForResponseAtEndpoint(server.endpoint(), HttpMethod.POST, 5,
                    checkpoint, JsonObject.class, server::releaseCurrentBody);

            assertEquals("current", response.get("id").getAsString());
            var captured = NetworkService.getCapturedEntries(checkpoint).stream()
                    .filter(entry -> entry.url().equals(server.endpoint())).findFirst().orElseThrow();
            assertTrue(captured.completed());
            assertEquals("fixture", captured.requestHeaders().get("x-project"));
            assertEquals("fixture", captured.responseHeaders().get("x-capture-reply"));
        }
    }

    @Test
    void handlesValidationErrorsAndBodylessDeletion() throws IOException {
        try (var server = new LocalServer()) {
            var page = open(server);
            var create = NetworkService.checkpoint();
            var response = NetworkService.waitForResponseAtEndpoint(server.endpoint(), HttpMethod.POST, 5,
                    create, JsonObject.class, () -> page.evaluate("""
                            async () => (await fetch('/record', {method: 'POST', body: '{"id":"invalid"}'})).json()
                            """));
            assertEquals("invalid", response.get("id").getAsString());
            assertEquals(422, NetworkService.waitForResponse(server.endpoint(), HttpMethod.POST, 0, create).status());

            var delete = NetworkService.checkpoint();
            page.evaluate("() => { fetch('/record', {method: 'DELETE'}); }");
            var deleted = NetworkService.waitForResponseAtEndpoint(server.endpoint(), HttpMethod.DELETE, 5, delete);

            assertEquals(204, deleted.status());
            assertTrue(deleted.completed());
            assertFalse(deleted.hasResponseBody());
            assertNull(NetworkService.getResponseByUrl(server.endpoint(), "DELETE", JsonObject.class, delete));
        }
    }

    @Test
    void unexpectedRequestAssertionDoesNotExposeCapturedHeadersOrBody() throws IOException {
        try (var server = new LocalServer()) {
            var page = open(server);
            page.evaluate("""
                    async () => (await fetch('/record', {
                        method: 'POST',
                        headers: {authorization: 'Bearer fixture-header-value'},
                        body: 'fixture-body-value'
                    })).json()
                    """);
            var entry = NetworkService.waitForResponse(server.endpoint(), HttpMethod.POST, 0);
            assertEquals("Bearer fixture-header-value", entry.requestHeaders().get("authorization"));
            assertEquals("fixture-body-value", entry.requestBody());
            NetworkService.assertRequestNotMade(server.endpoint(), HttpMethod.DELETE);

            var error = assertThrows(AssertionError.class,
                    () -> NetworkService.assertRequestNotMade(server.endpoint(), HttpMethod.POST));
            assertTrue(error.getMessage().contains("An unexpected POST request was captured for " + server.endpoint()));
            assertFalse(error.getMessage().contains("fixture-header-value"));
            assertFalse(error.getMessage().contains("fixture-body-value"));
        }
    }

    @Test
    void resetsHistoryAndRejectsCheckpointsAcrossBrowserContexts() throws IOException {
        try (var server = new LocalServer()) {
            var page = open(server);
            var context = page.context();
            var capture = Traffic.find(context);
            assertNotNull(capture);
            var checkpoint = NetworkService.checkpoint();
            assertThrows(IllegalStateException.class, () -> NetworkService.waitForResponseAtEndpoint(
                    server.endpoint(), HttpMethod.GET, 5, checkpoint, JsonObject.class,
                    NetworkService::newHar));
            assertTrue(NetworkService.getCapturedEntries().isEmpty());
            assertThrows(IllegalStateException.class, () -> NetworkService.getCapturedEntries(checkpoint));

            var beforeRequest = NetworkService.checkpoint();
            page.evaluate("() => { fetch('/record'); }");
            assertEquals(List.of(), NetworkService.waitForRequests(List.of(server.endpoint()), HttpMethod.GET, 5, beforeRequest));
            NetworkService.waitForResponse(server.endpoint(), HttpMethod.GET, 5, beforeRequest);
            assertFalse(NetworkService.getCapturedEntries().isEmpty());

            PlaywrightService.closeContext();
            assertNull(Traffic.find(context));
            assertThrows(IllegalStateException.class, () -> capture.entries(null));
            assertFalse(NetworkService.isCapturing());

            startBrowser();
            assertTrue(NetworkService.getCapturedEntries().isEmpty());
            assertThrows(IllegalStateException.class, () -> NetworkService.getCapturedEntries(beforeRequest));
        }
    }

    @Test
    void reportsWhenACompletedResponseExceedsTheBodyLimit() throws IOException {
        try (var server = new LocalServer()) {
            var page = open(server);
            var checkpoint = NetworkService.checkpoint();
            page.evaluate("() => { fetch('/record?oversize').then(response => response.text()); }");
            var entry = NetworkService.waitForResponse(candidate -> candidate.url().equals(server.endpoint() + "?oversize"),
                    WAIT, checkpoint);

            assertEquals(200, entry.status());
            assertNull(entry.responseBody());
            assertTrue(entry.responseBodyUnavailable().contains("capture limit"));
            var error = assertThrows(IllegalStateException.class, () -> NetworkService.getResponseByUrl(
                    server.endpoint() + "?oversize", "GET", JsonObject.class, checkpoint));
            assertTrue(error.getMessage().contains("capture limit"));
        }
    }

    @Test
    void failsAsSoonAsTheMatchingResponseIsRejected() throws IOException {
        try (LocalServer server = new LocalServer()) {
            Page page = open(server);
            String endpoint = server.endpoint() + "?rejected";
            String expected = "Expected GET response for " + endpoint
                    + " was not captured (status 500, completed true, response body captured true)";

            NetworkService.CaptureCheckpoint polled = NetworkService.checkpoint();
            page.evaluate("() => { fetch('/record?rejected'); }");
            TimeoutError polling = assertTimeout(WAIT, () -> assertThrows(TimeoutError.class,
                    () -> NetworkService.waitForResponse(endpoint, HttpMethod.GET, 30, polled)));
            assertEquals(expected, polling.getMessage());

            NetworkService.CaptureCheckpoint triggered = NetworkService.checkpoint();
            TimeoutError action = assertTimeout(WAIT, () -> assertThrows(TimeoutError.class,
                    () -> NetworkService.waitForResponseAtEndpoint(endpoint, HttpMethod.GET, 30, triggered,
                            JsonObject.class, () -> page.evaluate("() => { fetch('/record?rejected'); }"))));
            assertEquals(expected, action.getMessage());
        }
    }

    @Test
    void noCheckpointWaitReturnsLaterSuccess_when_rejectedResponseIsAlreadyInHistory() throws IOException {
        try (LocalServer server = new LocalServer()) {
            Page page = open(server);
            String rejected = server.endpoint() + "?rejected";
            page.evaluate("async () => (await fetch('/record?rejected')).text()");
            waitFor(page, () -> NetworkService.getCapturedEntries().stream()
                    .anyMatch(entry -> entry.url().equals(rejected) && entry.completed()));

            page.evaluate("() => { setTimeout(() => fetch('/record'), 500); }");
            CapturedRequest response = NetworkService.waitForResponse(server.endpoint(), HttpMethod.GET, 5);

            assertEquals(server.endpoint(), response.url());
            assertEquals(200, response.status());
        }
    }

    @Test
    void checkpointWaitReturnsRetriedSuccess_when_firstResponseIsUnauthorized() throws IOException {
        try (LocalServer server = new LocalServer()) {
            Page page = open(server);
            String endpoint = server.endpoint() + "?retry";
            NetworkService.CaptureCheckpoint checkpoint = NetworkService.checkpoint();
            page.evaluate("""
                    () => { fetch('/record?retry')
                        .then(() => new Promise(resolve => setTimeout(resolve, 500)))
                        .then(() => fetch('/record?retry')); }
                    """);

            CapturedRequest response = NetworkService.waitForResponse(endpoint, HttpMethod.GET, 5, checkpoint);

            assertEquals(200, response.status());
            assertEquals(List.of(401, 200), NetworkService.getCapturedEntries(checkpoint).stream()
                    .filter(entry -> entry.url().equals(endpoint)).map(CapturedRequest::status).toList());
        }
    }

    @Test
    void preservesTheBrowserActionTimeout() throws IOException {
        try (var server = new LocalServer()) {
            open(server);
            var checkpoint = NetworkService.checkpoint();
            var actionFailure = new TimeoutError("Browser action timed out");

            var actual = assertThrows(TimeoutError.class, () -> NetworkService.waitForResponseAtEndpoint(
                    server.endpoint(), HttpMethod.POST, 0, checkpoint, JsonObject.class,
                    () -> { throw actionFailure; }));

            assertSame(actionFailure, actual);
        }
    }

    @Test
    void sdkScriptBodiesDoNotEvictTheApiCheckpoint() throws IOException {
        try (var server = new LocalServer()) {
            var page = open(server);
            var checkpoint = NetworkService.checkpoint();
            page.evaluate("""
                    count => Promise.all(Array.from({length: count}, (_, index) => new Promise((resolve, reject) => {
                        const script = document.createElement('script');
                        script.src = '/sdk/' + index + '.js';
                        script.onload = () => resolve();
                        script.onerror = () => reject(new Error('SDK script did not load'));
                        document.head.append(script);
                    })))
                    """, SCRIPT_COUNT);
            waitFor(page, () -> NetworkService.getCapturedEntries(checkpoint).stream()
                    .filter(entry -> entry.resourceType().equals("script") && entry.completed()).count() == SCRIPT_COUNT);

            var scripts = NetworkService.getCapturedEntries(checkpoint).stream()
                    .filter(entry -> entry.resourceType().equals("script")).toList();
            assertEquals(SCRIPT_COUNT, scripts.size());
            assertTrue(scripts.stream().allMatch(entry -> Integer.valueOf(200).equals(entry.status())
                    && entry.responseSize() == SDK_SCRIPT.length && entry.responseBody() == null
                    && entry.responseBodyUnavailable().contains("script")));

            var response = NetworkService.waitForResponseAtEndpoint(server.endpoint(), HttpMethod.GET, 5,
                    checkpoint, JsonObject.class, () -> page.evaluate("async () => (await fetch('/record')).json()"));
            assertEquals("record", response.get("id").getAsString());
        }
    }

    private static Page open(LocalServer server) {
        var page = startBrowser();
        page.navigate(server.baseUrl());
        return page;
    }

    private static Page startBrowser() {
        return PlaywrightService.start(new BrowserConfiguration(BrowserTypes.CHROMIUM_HEADLESS,
                Lifecycle.RESTART_EVERY_TIME)).getCurrentPage();
    }

    private static void waitFor(Page page, BooleanSupplier condition) {
        page.context().waitForCondition(condition, new BrowserContext.WaitForConditionOptions().setTimeout(WAIT.toMillis()));
    }

    private static final class LocalServer implements AutoCloseable {
        private final ExecutorService executor = Executors.newFixedThreadPool(3);
        private final CountDownLatch oldBody = new CountDownLatch(1);
        private final CountDownLatch currentBody = new CountDownLatch(1);
        private final AtomicBoolean authorized = new AtomicBoolean();
        private final HttpServer server;

        private LocalServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> send(exchange, 200, "text/html",
                    "<!doctype html><title>Network capture</title>".getBytes(StandardCharsets.UTF_8), null));
            server.createContext("/record", this::record);
            server.createContext("/sdk/", exchange -> send(exchange, 200, "application/javascript", SDK_SCRIPT, null));
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String endpoint() {
            return baseUrl() + "/record";
        }

        private void releaseOldBody() {
            oldBody.countDown();
        }

        private void releaseCurrentBody() {
            currentBody.countDown();
        }

        private void record(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equals("DELETE")) {
                send(exchange, 204, "application/json", null, null);
                return;
            }
            if ("oversize".equals(exchange.getRequestURI().getQuery())) {
                send(exchange, 200, "application/json", new byte[2 * 1024 * 1024 + 1], null);
                return;
            }
            if ("rejected".equals(exchange.getRequestURI().getQuery())) {
                send(exchange, 500, "application/json", "{\"error\":\"rejected\"}".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            if ("retry".equals(exchange.getRequestURI().getQuery()) && !authorized.getAndSet(true)) {
                send(exchange, 401, "application/json", "{\"error\":\"expired\"}".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String id = switch (payload) {
                case "{\"id\":\"old\"}" -> "old";
                case "{\"id\":\"current\"}" -> "current";
                case "{\"id\":\"invalid\"}" -> "invalid";
                default -> "record";
            };
            var gate = switch (id) {
                case "old" -> oldBody;
                case "current" -> currentBody;
                default -> null;
            };
            byte[] body = "{\"data\":{\"id\":\"%s\"}}".formatted(id).getBytes(StandardCharsets.UTF_8);
            send(exchange, id.equals("invalid") ? 422 : 200, "application/json", body, gate);
        }

        private static void send(HttpExchange exchange, int status, String contentType, byte[] body, CountDownLatch gate)
                throws IOException {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("X-Capture-Reply", "fixture");
                exchange.sendResponseHeaders(status, body == null ? -1 : body.length);
                if (gate != null && !gate.await(15, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release the response body");
                }
                if (body != null) {
                    exchange.getResponseBody().write(body);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
        }

        @Override
        public void close() {
            releaseOldBody();
            releaseCurrentBody();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
