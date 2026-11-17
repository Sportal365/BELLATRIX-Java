package solutions.bellatrix.playwright.infrastructure;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import solutions.bellatrix.core.plugins.PluginExecutionEngine;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.plugins.TimeRecord;
import solutions.bellatrix.playwright.services.NetworkService;
import solutions.bellatrix.playwright.services.NetworkService.CaptureCheckpoint;
import solutions.bellatrix.playwright.utilities.Settings;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("browser-contract")
class ClassContextLifecycleTests {
    private static final String FIXTURE_URL = "https://fixture.test/";
    private AutoCloseable pluginScope;
    private BrowserLifecyclePlugin lifecycle;
    private String previousScope;

    @BeforeEach
    void useClassScope() {
        pluginScope = PluginExecutionEngine.openScope();
        lifecycle = new BrowserLifecyclePlugin();
        previousScope = Settings.web().getContextScope();
        Settings.web().setContextScope("class");
    }

    @AfterEach
    void closeBrowserAndRestoreScope() throws Exception {
        try {
            lifecycle.postAfterClass(Fixture.class);
        } finally {
            Settings.web().setContextScope(previousScope);
            pluginScope.close();
        }
    }

    @Test
    void storageAndPrimaryPageAreKeptButTestObservationsReset_when_nextTestOfTheClassStarts() throws Exception {
        lifecycle.preBeforeClass(Fixture.class);
        WrappedBrowser browser = start("first");
        BrowserContext context = browser.getCurrentContext();
        Page primary = browser.getCurrentPage();
        context.route(FIXTURE_URL + "**", route -> route.fulfill(new Route.FulfillOptions().setContentType("text/html")
                .setBody("<main>Fixture</main><button onclick=\"window.open('/popup')\">Open</button>")));
        AtomicReference<Route> heldRequest = new AtomicReference<>();
        context.route(FIXTURE_URL + "pending", heldRequest::set);
        primary.navigate(FIXTURE_URL);
        primary.evaluate("""
                () => {
                    localStorage.setItem('fixture', 'local');
                    sessionStorage.setItem('fixture', 'session');
                    document.cookie = 'fixture=cookie; path=/';
                    console.error('first test error');
                    const request = new XMLHttpRequest();
                    request.open('GET', '/pending');
                    request.send();
                }
                """);
        context.waitForCondition(() -> heldRequest.get() != null);
        CaptureCheckpoint firstTestCheckpoint = NetworkService.checkpoint();
        Page popup = primary.waitForPopup(() -> primary.getByText("Open").click());
        browser.setCurrentPage(popup);
        assertEquals(List.of("first test error"), browser.getConsoleErrors());
        assertTrue(browser.hasPendingAjax(primary));
        pass("first");

        assertSame(browser, start("second"));

        assertSame(context, browser.getCurrentContext());
        assertSame(primary, browser.getCurrentPage());
        assertTrue(popup.isClosed());
        assertEquals("local", primary.evaluate("() => localStorage.getItem('fixture')"));
        assertEquals("session", primary.evaluate("() => sessionStorage.getItem('fixture')"));
        assertEquals("fixture=cookie", primary.evaluate("() => document.cookie"));
        assertTrue(browser.getConsoleEntries().isEmpty());
        assertTrue(NetworkService.getCapturedEntries().isEmpty());
        assertThrows(IllegalStateException.class, () -> NetworkService.getCapturedEntries(firstTestCheckpoint));
        assertFalse(browser.hasPendingAjax(primary));
    }

    @Test
    void expectedConsoleErrorsEndWithTheirTest_when_nextTestOfTheClassStarts() throws Exception {
        lifecycle.preBeforeClass(Fixture.class);
        WrappedBrowser browser = start("first");
        browser.expectConsoleErrors(entry -> entry.message().contains("expected"));
        browser.getCurrentPage().evaluate("() => console.error('expected in the first test')");
        assertEquals(List.of(), browser.getConsoleErrors());
        pass("first");

        start("second");
        browser.getCurrentPage().evaluate("() => console.error('expected in the first test only')");

        assertEquals(List.of("expected in the first test only"), browser.getConsoleErrors());
    }

    @Test
    void contextIsReplacedAndBrowserKept_when_previousTestOfTheClassFailed() throws Exception {
        lifecycle.preBeforeClass(Fixture.class);
        WrappedBrowser browser = start("first");
        BrowserContext context = browser.getCurrentContext();
        Page page = browser.getCurrentPage();

        fail("first");

        assertTrue(page.isClosed());
        assertTrue(browser.getBrowser().isConnected());
        assertSame(browser, start("second"));
        assertNotSame(context, browser.getCurrentContext());
    }

    @Test
    void contextAndBrowserAreClosed_when_classEnds() throws Exception {
        lifecycle.preBeforeClass(Fixture.class);
        WrappedBrowser browser = start("first");
        Page page = browser.getCurrentPage();
        pass("first");

        lifecycle.postAfterClass(Fixture.class);

        assertTrue(page.isClosed());
        assertFalse(browser.getBrowser().isConnected());
        assertNull(PlaywrightService.wrappedBrowser());
    }

    @Test
    void testGetsItsOwnContext_when_itNeedsAnotherBrowserConfiguration() throws Exception {
        lifecycle.preBeforeClass(Fixture.class);
        WrappedBrowser browser = start("first");
        BrowserContext classContext = browser.getCurrentContext();
        pass("first");

        assertSame(browser, start("resized"));
        BrowserContext resizedContext = browser.getCurrentContext();
        assertNotSame(classContext, resizedContext);
        assertEquals(900, browser.getCurrentPage().viewportSize().width);
        pass("resized");

        WrappedBrowser restarting = start("restart");
        assertNotSame(resizedContext, restarting.getCurrentContext());
        Page restartPage = restarting.getCurrentPage();
        pass("restart");
        assertTrue(restartPage.isClosed());
        assertFalse(restarting.getBrowser().isConnected());
    }

    @Test
    void restartedContextIsKeptForTheNextTest_when_itWasStartedFromAStorageState() throws Exception {
        lifecycle.preBeforeClass(Fixture.class);
        WrappedBrowser browser = start("first");
        PlaywrightService.restartBrowserContext(browser.getCurrentContext().storageState());
        BrowserContext restored = browser.getCurrentContext();
        pass("first");

        start("second");

        assertSame(restored, browser.getCurrentContext());
    }

    @Test
    void everyTestGetsANewContext_when_scopeIsTest() throws Exception {
        Settings.web().setContextScope("test");
        lifecycle.preBeforeClass(Fixture.class);
        WrappedBrowser browser = start("first");
        BrowserContext context = browser.getCurrentContext();
        Page page = browser.getCurrentPage();

        pass("first");

        assertTrue(page.isClosed());
        assertSame(browser, start("second"));
        assertNotSame(context, browser.getCurrentContext());
    }

    @Test
    void classStartFails_when_scopeIsUnknown() {
        Settings.web().setContextScope("session");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> lifecycle.preBeforeClass(Fixture.class));

        assertTrue(failure.getMessage().contains("contextScope"));
        assertNull(PlaywrightService.wrappedBrowser());
    }

    private WrappedBrowser start(String name) throws Exception {
        lifecycle.preBeforeTest(TestResult.SUCCESS, method(name));
        return PlaywrightService.wrappedBrowser();
    }

    private void pass(String name) throws Exception {
        lifecycle.postAfterTest(TestResult.SUCCESS, new TimeRecord(), method(name), null);
    }

    private void fail(String name) throws Exception {
        AssertionError original = new AssertionError("fixture test failed");
        lifecycle.postAfterTest(TestResult.FAILURE, new TimeRecord(), method(name), original);
        assertEquals(0, original.getSuppressed().length);
    }

    private Method method(String name) throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod(name);
    }

    @ExecutionBrowser(lifecycle = Lifecycle.REUSE_IF_STARTED)
    private static class Fixture {
        void first() {
        }

        void second() {
        }

        @ExecutionBrowser(lifecycle = Lifecycle.RESTART_EVERY_TIME)
        void restart() {
        }

        @ExecutionBrowser(lifecycle = Lifecycle.REUSE_IF_STARTED, width = 900, height = 700)
        void resized() {
        }
    }
}
