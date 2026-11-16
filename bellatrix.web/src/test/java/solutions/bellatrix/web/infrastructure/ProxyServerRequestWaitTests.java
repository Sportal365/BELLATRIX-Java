package solutions.bellatrix.web.infrastructure;

import net.lightbody.bmp.core.har.HarEntry;
import net.lightbody.bmp.core.har.HarRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.support.ui.FluentWait;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class ProxyServerRequestWaitTests {
    private TestClock clock;
    private Runnable onPoll;

    @BeforeEach
    void setup() throws Exception {
        removeProxy();
        ProxyServer.newHar();
        clock = new TestClock();
        onPoll = () -> { };
    }

    @AfterEach
    void cleanup() throws Exception {
        removeProxy();
    }

    @Test
    void alreadyCapturedRequestsReturnWithoutSleeping() {
        capture("/authors", HttpMethod.GET);
        capture("/categories", HttpMethod.GET);

        assertTrue(ProxyServer.waitForRequests(List.of("/authors", "/categories"), HttpMethod.GET, waitFor()).isEmpty());
        assertEquals(Duration.ZERO, clock.elapsed);
    }

    @Test
    void waitsForDelayedRequestsWithinOneSharedDeadline() {
        capture("/authors", HttpMethod.GET);
        onPoll = () -> {
            if (clock.elapsed.equals(Duration.ofSeconds(3))) {
                capture("/categories", HttpMethod.GET);
            }
            if (clock.elapsed.equals(Duration.ofSeconds(5))) {
                capture("/sports", HttpMethod.GET);
            }
        };

        assertTrue(ProxyServer.waitForRequests(List.of("/authors", "/categories", "/sports"), HttpMethod.GET, waitFor()).isEmpty());
        assertEquals(Duration.ofSeconds(5), clock.elapsed);
    }

    @Test
    void missingOptionalRequestsReturnTogetherWithoutFailingOrMultiplyingTimeout() {
        var optionalUrls = IntStream.range(0, 40).mapToObj(index -> "/optional/" + index).toList();

        assertEquals(optionalUrls, assertDoesNotThrow(() ->
                ProxyServer.waitForRequests(optionalUrls, HttpMethod.GET, waitFor())));
        assertTrue(clock.elapsed.compareTo(Duration.ofSeconds(30)) >= 0);
        assertTrue(clock.elapsed.compareTo(Duration.ofSeconds(31)) <= 0);
    }

    @Test
    void matchesMethodAndReportsOnlyRequestsStillMissing() {
        capture("/authors?limit=200", HttpMethod.GET);
        capture("/categories", HttpMethod.POST);

        assertEquals(List.of("/categories"), ProxyServer.waitForRequests(
                List.of("/authors", "/categories", "/categories"), HttpMethod.GET, waitFor()));
    }

    @Test
    void actionWaitIgnoresEarlierRequestsAndAcceptsANewRequestBeforeItsResponse() {
        capture("/articles", HttpMethod.POST);
        var checkpoint = ProxyServer.checkpoint();
        onPoll = () -> {
            if (clock.elapsed.equals(Duration.ofSeconds(3))) {
                capture("/articles", HttpMethod.POST);
            }
        };

        ProxyServer.waitForRequest("/articles", HttpMethod.POST, waitFor(), checkpoint);
        assertEquals(Duration.ofSeconds(3), clock.elapsed);
    }

    @Test
    void actionRequestWaitRejectsAResetCapture() {
        var checkpoint = ProxyServer.checkpoint();
        ProxyServer.newHar();
        capture("/articles", HttpMethod.POST);

        assertThrows(IllegalStateException.class, () ->
                ProxyServer.waitForRequest("/articles", HttpMethod.POST, waitFor(), checkpoint));
    }

    @Test
    void emptyRequestListDoesNotPoll() {
        onPoll = () -> fail("Empty request list should not poll");

        assertEquals(List.of(), ProxyServer.waitForRequests(List.of(), HttpMethod.GET, waitFor()));
        assertEquals(Duration.ZERO, clock.elapsed);
    }

    @Test
    void missingHarStillUsesBoundedBestEffortObservation() throws Exception {
        removeProxy();

        assertEquals(List.of("/optional"), ProxyServer.waitForRequests(List.of("/optional"), HttpMethod.GET, waitFor()));
        assertTrue(clock.elapsed.compareTo(Duration.ofSeconds(31)) <= 0);
    }

    private FluentWait<WebDriver> waitFor() {
        return new FluentWait<WebDriver>(null, clock, duration -> {
            clock.elapsed = clock.elapsed.plus(duration);
            onPoll.run();
        }).withTimeout(Duration.ofSeconds(30)).pollingEvery(Duration.ofSeconds(1));
    }

    private void capture(String path, HttpMethod method) {
        var entry = new HarEntry("bootstrap");
        entry.setRequest(new HarRequest(method.toString(), "https://example.test" + path, "HTTP/1.1"));
        ProxyServer.get().getHar().getLog().addEntry(entry);
    }

    private void removeProxy() throws Exception {
        var field = ProxyServer.class.getDeclaredField("PROXY_SERVER");
        field.setAccessible(true);
        ((ThreadLocal<?>) field.get(null)).remove();
    }

    private static final class TestClock extends Clock {
        private Duration elapsed = Duration.ZERO;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.EPOCH.plus(elapsed);
        }
    }
}
