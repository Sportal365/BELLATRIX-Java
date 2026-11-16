package solutions.bellatrix.playwright.infrastructure;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import plugins.screenshots.ScreenshotPlugin;
import plugins.screenshots.ScreenshotPluginEventArgs;
import solutions.bellatrix.core.plugins.PluginExecutionEngine;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.plugins.TimeRecord;
import solutions.bellatrix.core.utilities.SingletonFactory;
import solutions.bellatrix.playwright.configuration.TimeoutSettings;
import solutions.bellatrix.playwright.infrastructure.junit.WebTest;
import solutions.bellatrix.playwright.services.BrowserService;
import solutions.bellatrix.playwright.services.NetworkService;
import solutions.bellatrix.playwright.utilities.Settings;

import javax.imageio.ImageIO;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("browser-contract")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExecutionBrowser(lifecycle = Lifecycle.REUSE_IF_STARTED)
class BrowserLifecycleTests extends WebTest {
    private Browser browser;
    private Page firstPage;
    private Page popup;

    @Test
    @Order(1)
    void installsContextSettingsAndCapturesInitialConsoleTraffic() {
        browser = app().browser().context().browser();
        firstPage = app().browser().page();
        serveFixture("""
                <button onclick="window.open('/popup')">Open</button>
                <script>
                  console.error('fixture startup error');
                  console.warn('fixture startup warning');
                  document.cookie = 'fixture=first; path=/';
                </script>
                """);
        app().navigate().to("https://fixture.test/");
        app().browser().setItemInLocalStorage("fixture", "first");
        assertEquals("first", app().browser().getItemFromLocalStorage("fixture"));
        assertEquals("first", app().browser().context().cookies().getFirst().value);
        assertEquals(List.of("fixture startup error"), app().browser().consoleErrors());
        app().browser().assertConsoleErrorLogged("fixture startup warning", Level.WARNING);
        assertTrue(app().browser().consoleEntries().stream().allMatch(entry -> entry.timestamp() > 0));
        assertEquals("en-GB", firstPage.evaluate("navigator.language"));
        assertEquals("Europe/Sofia", firstPage.evaluate("Intl.DateTimeFormat().resolvedOptions().timeZone"));
        assertEquals(800, firstPage.viewportSize().width);
        assertEquals(600, firstPage.viewportSize().height);
        popup = app().browser().runAndWaitForPopup(() -> firstPage.getByText("Open").click());
        assertSame(firstPage, popup.opener());
        assertSame(firstPage, app().browser().page());
        app().browser().switchToTab(popup);
        assertSame(popup, app().browser().page());
    }

    @Test
    @Order(2)
    void reusesBrowserWithIsolatedContextAndClosesPopup() {
        assertSame(browser, app().browser().context().browser());
        assertTrue(firstPage.isClosed());
        assertTrue(popup.isClosed());
        assertEquals(1, app().browser().context().pages().size());
        serveFixture("<main>New context</main>");
        app().navigate().to("https://fixture.test/");
        assertNull(app().browser().getItemFromLocalStorage("fixture"));
        assertTrue(app().browser().context().cookies().isEmpty());
        assertTrue(app().browser().consoleErrors().isEmpty());
        browser.close();
    }

    @Test
    @Order(3)
    void recoversAfterBrowserDisconnect() {
        assertNotSame(browser, app().browser().context().browser());
        assertTrue(app().browser().context().browser().isConnected());
        serveFixture("<main>Recovered</main>");
        app().navigate().to("https://fixture.test/");
        assertEquals("Recovered", app().browser().page().locator("main").innerText());
    }

    @Test
    @Order(4)
    void capturesFailureBeforeClosingPageAndKeepsOriginalFailure() throws Exception {
        var page = app().browser().page();
        page.setContent("<main>Failure screenshot fixture</main><div style='height: 900px'></div>");
        var captured = new AtomicReference<Path>();
        var artifactFailure = new IllegalStateException("fixture attachment listener failed");
        Consumer<ScreenshotPluginEventArgs> listener = screenshot -> {
            assertFalse(page.isClosed());
            captured.set(Path.of(screenshot.getScreenshotPath()));
            throw artifactFailure;
        };
        ScreenshotPlugin.SCREENSHOT_GENERATED.addListener(listener);
        try {
            var originalFailure = new AssertionError("fixture test failure");
            var method = BrowserLifecycleTests.class.getDeclaredMethod("capturesFailureBeforeClosingPageAndKeepsOriginalFailure");
            SingletonFactory.getInstance(BrowserLifecyclePlugin.class)
                    .postAfterTest(TestResult.FAILURE, new TimeRecord(), method, originalFailure);
            assertArrayEquals(new Throwable[]{artifactFailure}, originalFailure.getSuppressed());
            assertTrue(page.isClosed());
            assertNull(PlaywrightService.wrappedBrowser());
            var png = ImageIO.read(captured.get().toFile());
            assertEquals(800, png.getWidth());
            assertTrue(png.getHeight() > 900);
        } finally {
            ScreenshotPlugin.SCREENSHOT_GENERATED.removeListener(listener);
        }
    }

    @Test
    @Order(5)
    void propagatesInvalidContextStartupAndCleansUpForRetry() {
        var oldBrowser = app().browser().context().browser();
        var oldPage = app().browser().page();
        var configuration = PlaywrightService.browserConfiguration();
        var context = Settings.context();
        var timezone = context.getTimezoneId();
        try {
            context.setTimezoneId("Invalid/Timezone");
            assertThrows(PlaywrightException.class, () -> PlaywrightService.start(configuration));
            assertNull(PlaywrightService.wrappedBrowser());
            assertTrue(oldPage.isClosed());
            assertFalse(oldBrowser.isConnected());
        } finally {
            context.setTimezoneId(timezone);
        }
        assertTrue(PlaywrightService.start(configuration).getBrowser().isConnected());
    }

    @Test
    @Order(6)
    void pendingAjaxTimeoutIsAdvisoryAndCompletionIsObservable() {
        var browserService = app().browser();
        var pending = new AtomicReference<Route>();
        serveFixture("<main>AJAX fixture</main>");
        browserService.context().route("https://fixture.test/queued", pending::set);
        app().navigate().to("https://fixture.test/");
        browserService.page().evaluate("""
                () => {
                    const request = new XMLHttpRequest();
                    request.open('GET', '/queued');
                    request.send();
                }
                """);
        browserService.waitForCondition(() -> pending.get() != null);

        var timeoutSettings = Settings.timeout();
        long originalTimeout = timeoutSettings.getWaitForAjaxTimeout();
        try {
            timeoutSettings.setWaitForAjaxTimeout(1);
            assertFalse(browserService.waitForAjaxToComplete());
            assertDoesNotThrow(browserService::waitForAjax);
            pending.get().fulfill(new Route.FulfillOptions().setBody("completed"));
            assertTrue(browserService.waitForAjaxToComplete());
        } finally {
            timeoutSettings.setWaitForAjaxTimeout(originalTimeout);
        }
    }

    @Test
    @Order(7)
    void ajaxObservationPropagatesAClosedBrowser() {
        var browserService = app().browser();
        browserService.context().browser().close();
        var failure = assertThrows(PlaywrightException.class, browserService::waitForAjax);
        assertEquals("Cannot observe AJAX after the browser page closed", failure.getMessage());
    }

    @Test
    @Order(8)
    void waitsForAjaxOnlyInTheSelectedPageMainFrame() {
        var browserService = app().browser();
        var mainPage = browserService.page();
        var frameRequest = new AtomicReference<Route>();
        var popupRequest = new AtomicReference<Route>();
        var popupPath = "/pending-popup?fixture=private";
        serveFixture("<main>XHR fixture</main>");
        browserService.context().route("https://fixture.test/", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html").setBody("""
                        <iframe src="/child"></iframe>
                        <button onclick="window.open('/popup')">Open popup</button>
                        """)));
        browserService.context().route("https://fixture.test/pending-frame", frameRequest::set);
        browserService.context().route("https://fixture.test" + popupPath, popupRequest::set);
        app().navigate().to("https://fixture.test/");
        var childFrame = mainPage.frameByUrl("https://fixture.test/child");
        assertNotNull(childFrame);
        var startRequest = """
                path => {
                    const request = new XMLHttpRequest();
                    request.open('GET', path);
                    request.send();
                }
                """;
        childFrame.evaluate(startRequest, "/pending-frame");
        var popupPage = browserService.runAndWaitForPopup(() -> mainPage.getByText("Open popup").click());
        popupPage.waitForLoadState();
        popupPage.evaluate(startRequest, popupPath);
        browserService.waitForCondition(() -> frameRequest.get() != null && popupRequest.get() != null);

        var timeoutSettings = Settings.timeout();
        long originalTimeout = timeoutSettings.getWaitForAjaxTimeout();
        try {
            timeoutSettings.setWaitForAjaxTimeout(1);
            assertTrue(browserService.waitForAjaxToComplete());
            browserService.switchToTab(popupPage);
            assertFalse(browserService.waitForAjaxToComplete());
            var summary = PlaywrightService.wrappedBrowser().pendingAjaxSummary(popupPage);
            assertTrue(summary.startsWith("1 pending top-level XHR requests on the current page, 0 on other pages. "
                    + "Current-page origin sample [https://fixture.test]. Request sample [#1 GET /pending-popup "));
            assertTrue(summary.endsWith(" awaiting response headers]"));
            assertFalse(summary.contains("fixture=private"));
            popupRequest.get().fulfill(new Route.FulfillOptions().setBody("popup done"));
            assertTrue(browserService.waitForAjaxToComplete());
            browserService.switchToTab(mainPage);
            frameRequest.get().fulfill(new Route.FulfillOptions().setBody("frame done"));
        } finally {
            timeoutSettings.setWaitForAjaxTimeout(originalTimeout);
        }
    }

    @Test
    @Order(9)
    void tracksAjaxForTheCurrentDocumentAcrossFullAndSameDocumentNavigation() {
        var browserService = app().browser();
        var page = browserService.page();
        var browser = PlaywrightService.wrappedBrowser();
        var firstRequest = new AtomicReference<Route>();
        var secondRequest = new AtomicReference<Route>();
        serveFixture("""
                <script>
                    const request = new XMLHttpRequest();
                    request.open('GET', '/pending' + location.pathname);
                    request.send();
                </script>
                <main>XHR fixture</main>
                """);
        browserService.context().route("https://fixture.test/pending/first", firstRequest::set);
        browserService.context().route("https://fixture.test/pending/second", secondRequest::set);

        app().navigate().to("https://fixture.test/first");
        browserService.waitForCondition(() -> firstRequest.get() != null);
        var firstDocument = page.evaluate("() => performance.timeOrigin");
        assertTrue(browser.pendingAjaxSummary(page).contains("Request sample [#1 GET "));

        app().navigate().to("https://fixture.test/second");
        browserService.waitForCondition(() -> secondRequest.get() != null);
        var secondDocument = page.evaluate("() => performance.timeOrigin");
        assertNotEquals(firstDocument, secondDocument);
        var summary = browser.pendingAjaxSummary(page);
        assertTrue(summary.startsWith("1 pending top-level XHR requests"));
        assertTrue(summary.contains("Request sample [#2 GET "));

        page.evaluate("() => history.pushState(null, '', '/second#still-open')");
        assertEquals(secondDocument, page.evaluate("() => performance.timeOrigin"));
        assertTrue(browser.pendingAjaxSummary(page).contains("Request sample [#2 GET "));
        secondRequest.get().fulfill(new Route.FulfillOptions().setBody("completed"));
        assertTrue(browserService.waitForAjaxToComplete());
    }

    private void serveFixture(String html) {
        app().browser().context().route("https://fixture.test/**", route ->
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody(html)));
    }

    @Test
    void formatsOnlyTheBoundedHttpRequestPath() {
        assertEquals("/v2/players/32%2F43", WrappedBrowser.requestPath(
                "https://private-user:private-password@fixture.test:8443/v2/players/32%2F43?key=private#fragment"));
        assertEquals("/", WrappedBrowser.requestPath("https://fixture.test?key=private"));
        assertEquals("(unknown path)", WrappedBrowser.requestPath("data:text/html,private"));
        assertEquals("(unknown path)", WrappedBrowser.requestPath("https://fixture.test/bad%encoding"));
        assertEquals("/" + "a".repeat(199) + "...", WrappedBrowser.requestPath("https://fixture.test/" + "a".repeat(220)));
    }

    @Test
    void masksEnvironmentSecretsInRequestPaths() {
        assumeTrue("ajax-fixture-value".equals(System.getenv("AJAX_DIAGNOSTIC_FIXTURE_SECRET")));
        assertEquals("/fixture/[REDACTED]/details", WrappedBrowser.requestPath("https://fixture.test/fixture/ajax-fixture-value/details"));
        assertEquals("(redacted path)", WrappedBrowser.requestPath("https://fixture.test/fixture/ajax%2Dfixture%2Dvalue/details"));
    }

    @Test
    @Order(10)
    void waitsForCompletedResourcesAfterResourceTimingFillsWithCaptureDisabled() {
        var browser = app().browser();
        var context = browser.context();
        var page = browser.page();
        serveFixture("<main>Resource fixture</main>");
        context.route("https://fixture.test/resource/**", route ->
                route.fulfill(new Route.FulfillOptions().setBody("fixture")));
        NetworkService.uninstall(context);
        assertFalse(NetworkService.isCapturing());
        app().navigate().to("https://fixture.test/");
        var resourceWasRecorded = page.evaluate("""
                async () => {
                    performance.setResourceTimingBufferSize(1);
                    await fetch('/resource/filler').then(response => response.text());
                    await fetch('/resource/target').then(response => response.text());
                    return performance.getEntriesByName(location.origin + '/resource/target').length > 0;
                }
                """);
        assertEquals(Boolean.FALSE, resourceWasRecorded);
        browser.waitForRequest("/resource/TARGET");
        page.evaluate("() => history.pushState(null, '', '#same-document')");
        browser.waitForRequest("/resource/target");
        app().navigate().to("https://fixture.test/second");
        var timeoutSettings = Settings.timeout();
        long originalTimeout = timeoutSettings.getWaitUntilReadyTimeout();
        try {
            timeoutSettings.setWaitUntilReadyTimeout(1);
            assertThrows(TimeoutError.class, () -> browser.waitForRequest("/resource/target"));
        } finally {
            timeoutSettings.setWaitUntilReadyTimeout(originalTimeout);
        }
    }

    @Test
    @Order(11)
    void resourceWaitPropagatesPageClosure() {
        var browser = app().browser();
        serveFixture("<main>Popup fixture</main>");
        var popup = browser.runAndWaitForPopup(() -> browser.page().evaluate("() => window.open('https://fixture.test/')"));
        browser.switchToTab(popup);
        popup.evaluate("() => { setTimeout(() => window.close(), 100); }");

        var failure = assertThrows(PlaywrightException.class, () -> browser.waitForRequest("/unobserved"));

        assertTrue(popup.isClosed());
        assertFalse(failure instanceof TimeoutError);
    }

    @Test
    @Order(12)
    void detachingAFrameWithAnInflightRequestKeepsTopLevelResourceObservationWorking() {
        var browser = app().browser();
        var page = browser.page();
        serveFixture("<main>Resource fixture</main>");
        browser.context().route("https://fixture.test/", route -> route.fulfill(new Route.FulfillOptions()
                .setContentType("text/html").setBody("<iframe src='/child'></iframe>")));
        var pending = new AtomicReference<Route>();
        browser.context().route("https://fixture.test/pending", pending::set);
        app().navigate().to("https://fixture.test/");
        var child = page.frameByUrl("https://fixture.test/child");
        assertNotNull(child);
        child.evaluate("() => { fetch('/pending').catch(() => {}); }");
        browser.waitForCondition(() -> pending.get() != null);
        page.locator("iframe").evaluate("frame => frame.remove()");
        page.evaluate("async () => (await fetch('/top-level')).text()");

        browser.waitForRequest("/top-level");
    }

    @Test
    @Order(13)
    void oneScreenshotIsTakenPerFailure_when_severalTestsOfAClassFail() throws Exception {
        Method method = BrowserLifecycleTests.class.getDeclaredMethod("oneScreenshotIsTakenPerFailure_when_severalTestsOfAClassFail");
        AtomicInteger screenshots = new AtomicInteger();
        Consumer<ScreenshotPluginEventArgs> listener = screenshot -> screenshots.incrementAndGet();
        ScreenshotPlugin.SCREENSHOT_GENERATED.addListener(listener);
        try {
            for (int failures = 1; failures <= 3; failures++) {
                failThroughThePluginEngine(method);
                assertEquals(failures, screenshots.get());
                PlaywrightService.restartBrowserContext();
            }
        } finally {
            ScreenshotPlugin.SCREENSHOT_GENERATED.removeListener(listener);
        }
    }

    @Test
    @Order(14)
    void contextStartsWithTheSuppliedCookiesAndLocalStorage_when_restartedFromAStorageState() {
        serveFixture("<main>Stored session</main>");
        app().navigate().to("https://fixture.test/");
        app().browser().setItemInLocalStorage("session", "local");
        app().browser().context().addCookies(List.of(new Cookie("session", "cookie").setUrl("https://fixture.test/")));
        String storageState = app().browser().context().storageState();
        Page previousPage = app().browser().page();

        PlaywrightService.restartBrowserContext(storageState);

        assertTrue(previousPage.isClosed());
        serveFixture("<main>Restored session</main>");
        app().navigate().to("https://fixture.test/");
        assertEquals("local", app().browser().getItemFromLocalStorage("session"));
        assertEquals("cookie", app().browser().context().cookies().getFirst().value);
    }

    @Test
    @Order(15)
    void contextStartsEmpty_when_restartedWithoutAStorageState() {
        serveFixture("<main>Stored session</main>");
        app().navigate().to("https://fixture.test/");
        app().browser().setItemInLocalStorage("session", "local");
        PlaywrightService.restartBrowserContext(app().browser().context().storageState());

        PlaywrightService.restartBrowserContext();

        serveFixture("<main>Empty session</main>");
        app().navigate().to("https://fixture.test/");
        assertNull(app().browser().getItemFromLocalStorage("session"));
        assertTrue(app().browser().context().cookies().isEmpty());
    }

    @Test
    @Order(16)
    void onlyLaterXhrsAreAwaited_when_pendingAjaxWasForgotten() {
        BrowserService browserService = app().browser();
        Page page = browserService.page();
        WrappedBrowser browser = PlaywrightService.wrappedBrowser();
        AtomicReference<Route> hung = new AtomicReference<>();
        AtomicReference<Route> later = new AtomicReference<>();
        serveFixture("<main>Forgotten AJAX fixture</main>");
        browserService.context().route("https://fixture.test/hung", hung::set);
        browserService.context().route("https://fixture.test/later", later::set);
        app().navigate().to("https://fixture.test/");
        startXhr(page, "/hung");
        browserService.waitForCondition(() -> hung.get() != null);
        assertTrue(browser.hasPendingAjax(page));

        TimeoutSettings timeoutSettings = Settings.timeout();
        long originalTimeout = timeoutSettings.getWaitForAjaxTimeout();
        try {
            timeoutSettings.setWaitForAjaxTimeout(1);
            browser.forgetPendingAjax();

            assertTrue(browserService.waitForAjaxToComplete());
            startXhr(page, "/later");
            browserService.waitForCondition(() -> later.get() != null);
            assertTrue(browser.hasPendingAjax(page));
            later.get().fulfill(new Route.FulfillOptions().setBody("later done"));
            assertTrue(browserService.waitForAjaxToComplete());
            assertFalse(browser.hasPendingAjax(page));
        } finally {
            timeoutSettings.setWaitForAjaxTimeout(originalTimeout);
        }
    }

    @Test
    @Order(17)
    void hungXhrIsAwaitedOnce_when_itOutlivedAWholeWait() {
        BrowserService browserService = app().browser();
        Page page = browserService.page();
        WrappedBrowser browser = PlaywrightService.wrappedBrowser();
        AtomicReference<Route> hung = new AtomicReference<>();
        AtomicReference<Route> later = new AtomicReference<>();
        serveFixture("<main>Hung AJAX fixture</main>");
        browserService.context().route("https://fixture.test/hung", hung::set);
        browserService.context().route("https://fixture.test/later", later::set);
        app().navigate().to("https://fixture.test/");
        startXhr(page, "/hung");
        browserService.waitForCondition(() -> hung.get() != null);

        TimeoutSettings timeoutSettings = Settings.timeout();
        long originalTimeout = timeoutSettings.getWaitForAjaxTimeout();
        try {
            timeoutSettings.setWaitForAjaxTimeout(1);
            assertFalse(browserService.waitForAjaxToComplete());
            assertTrue(browserService.waitForAjaxToComplete(), "The hung request must not cost a second wait");
            assertTrue(browser.pendingAjaxSummary(page).startsWith("1 pending top-level XHR requests"));

            startXhr(page, "/later");
            browserService.waitForCondition(() -> later.get() != null);
            assertTrue(browser.hasPendingAjax(page), "A request started after the timeout is still awaited");
            later.get().fulfill(new Route.FulfillOptions().setBody("later done"));
            assertTrue(browserService.waitForAjaxToComplete());
        } finally {
            timeoutSettings.setWaitForAjaxTimeout(originalTimeout);
        }
    }

    @Test
    @Order(18)
    void xhrStartedDuringAWaitIsStillAwaited_when_olderXhrsAreNoLongerAwaited() {
        BrowserService browserService = app().browser();
        Page page = browserService.page();
        WrappedBrowser browser = PlaywrightService.wrappedBrowser();
        AtomicReference<Route> hung = new AtomicReference<>();
        AtomicReference<Route> later = new AtomicReference<>();
        serveFixture("<main>Hung and later AJAX fixture</main>");
        browserService.context().route("https://fixture.test/hung", hung::set);
        browserService.context().route("https://fixture.test/later", later::set);
        app().navigate().to("https://fixture.test/");
        startXhr(page, "/hung");
        browserService.waitForCondition(() -> hung.get() != null);
        long latestWhenTheWaitBegan = browser.latestAjaxSequence();
        startXhr(page, "/later");
        browserService.waitForCondition(() -> later.get() != null);

        browser.stopAwaitingAjax(page, latestWhenTheWaitBegan);

        assertTrue(browser.hasPendingAjax(page));
        later.get().fulfill(new Route.FulfillOptions().setBody("later done"));
        assertTrue(browserService.waitForAjaxToComplete());
    }

    @Test
    @Order(19)
    void pageIsLoadedAgain_when_navigatingToTheUrlItAlreadyShows() {
        Page page = app().browser().page();
        AtomicInteger documentLoads = new AtomicInteger();
        app().browser().context().route("https://fixture.test/**", route -> {
            documentLoads.incrementAndGet();
            route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<main>Hash route fixture</main>"));
        });

        app().navigate().to("https://fixture.test/#/list");
        assertEquals(1, documentLoads.get());
        app().navigate().to("https://fixture.test/#/other");
        assertEquals(1, documentLoads.get(), "A different fragment is a navigation inside the same document");
        assertEquals("https://fixture.test/#/other", page.url());

        app().navigate().to("https://fixture.test/#/other");
        assertEquals(2, documentLoads.get());
        assertEquals("https://fixture.test/#/other", page.url());
    }

    private static void startXhr(Page page, String path) {
        page.evaluate("""
                path => {
                    const request = new XMLHttpRequest();
                    request.open('GET', path);
                    request.send();
                }
                """, path);
    }

    @SuppressWarnings("deprecation")
    private static void failThroughThePluginEngine(Method method) {
        AssertionError failure = new AssertionError("fixture test failure");
        PluginExecutionEngine.postAfterTest(TestResult.FAILURE, method, failure);
        PluginExecutionEngine.postAfterTest(TestResult.FAILURE, new TimeRecord(), method, failure);
    }
}
