package solutions.bellatrix.web.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import solutions.bellatrix.core.plugins.PluginExecutionEngine;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.web.configuration.WebSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class BrowserLifecycleTests {
    private final List<BrowserConfiguration> started = new ArrayList<>();
    private final AtomicInteger closed = new AtomicInteger();
    private BrowserLifecyclePlugin plugin;

    @AfterEach
    void clearLifecycle() {
        if (plugin != null) {
            try {
                plugin.postAfterClass(getClass());
            } finally {
                PluginExecutionEngine.removePlugin(plugin);
            }
        }
    }

    private WebSettings settings() {
        var settings = new WebSettings();
        settings.setExecutionType("grid");
        settings.setDefaultBrowser("chrome");
        settings.setDefaultLifeCycle("reuse if started");
        settings.setDefaultBrowserWidth(1280);
        settings.setDefaultBrowserHeight(720);
        return settings;
    }

    private BrowserLifecyclePlugin lifecycle() {
        plugin = new BrowserLifecyclePlugin(this::settings, started::add, closed::incrementAndGet, () -> { }, () -> true);
        return plugin;
    }

    @Test
    void compactsTrafficBetweenTestsEvenWhenSuiteDoesNotResetItsOwnCapture() throws Exception {
        var captures = new AtomicInteger();
        var webSettings = settings();
        webSettings.setShouldCaptureHttpTraffic(true);
        plugin = new BrowserLifecyclePlugin(() -> webSettings, started::add, closed::incrementAndGet,
                captures::incrementAndGet, () -> true);
        plugin.preBeforeClass(PlainClass.class);
        var method = PlainClass.class.getMethod("test");
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(1, captures.get());
        plugin.postAfterTest(TestResult.FAILURE, method, new AssertionError("failed test"));
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(3, captures.get());
        assertEquals(1, started.size());
        plugin.postAfterClass(PlainClass.class);
        plugin.preBeforeClass(PlainClass.class);
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(4, captures.get());
    }

    @Test
    void doesNotCreateCapturesWhenTrafficRecordingIsDisabled() throws Exception {
        var webSettings = settings();
        webSettings.setShouldCaptureHttpTraffic(false);
        plugin = new BrowserLifecyclePlugin(() -> webSettings, started::add, closed::incrementAndGet,
                () -> fail("Traffic recording is disabled"), () -> true);
        plugin.preBeforeClass(PlainClass.class);
        var method = PlainClass.class.getMethod("test");
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        plugin.preBeforeTest(TestResult.SUCCESS, method);
    }

    @Test
    void reusesWithinClassAndClosesBetweenClasses() throws Exception {
        lifecycle().preBeforeClass(PlainClass.class);
        plugin.preBeforeTest(TestResult.SUCCESS, PlainClass.class.getMethod("test"));
        plugin.postAfterTest(TestResult.SUCCESS, PlainClass.class.getMethod("test"), null);
        plugin.preBeforeTest(TestResult.SUCCESS, PlainClass.class.getMethod("test"));
        assertEquals(1, started.size());
        plugin.postAfterClass(PlainClass.class);
        plugin.preBeforeClass(PlainClass.class);
        assertEquals(2, started.size());
    }

    @Test
    void replacesDeadReusedSessionBeforeNextTest() throws Exception {
        var usable = new java.util.concurrent.atomic.AtomicBoolean(true);
        plugin = new BrowserLifecyclePlugin(this::settings, configuration -> {
            started.add(configuration);
            usable.set(true);
        }, closed::incrementAndGet, () -> { }, usable::get);
        plugin.preBeforeClass(PlainClass.class);
        var method = PlainClass.class.getMethod("test");
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        var originalFailure = new org.openqa.selenium.WebDriverException("tab crashed");
        plugin.postAfterTest(TestResult.FAILURE, method, originalFailure);
        assertEquals(1, started.size());
        usable.set(false);
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(2, started.size());
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(2, started.size());
    }

    @Test
    void recoversEvenWhenDeadSessionRejectsQuit() throws Exception {
        var usable = new java.util.concurrent.atomic.AtomicBoolean(true);
        plugin = new BrowserLifecyclePlugin(this::settings, configuration -> {
            started.add(configuration);
            usable.set(true);
        }, () -> {
            if (!usable.get()) throw new org.openqa.selenium.WebDriverException("browser disconnected");
        }, () -> { }, usable::get);
        plugin.preBeforeClass(PlainClass.class);
        usable.set(false);
        plugin.preBeforeTest(TestResult.SUCCESS, PlainClass.class.getMethod("test"));
        assertEquals(2, started.size());
    }

    @Test
    void explicitRestartOverridesSharedDefault() throws Exception {
        lifecycle().preBeforeClass(RestartClass.class);
        var method = RestartClass.class.getMethod("test");
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        plugin.postAfterTest(TestResult.SUCCESS, method, null);
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(2, started.size());
    }

    @Test
    void lifecycleOnlyMethodOverrideRetainsBrowserAndDimensions() throws Exception {
        lifecycle().preBeforeClass(MethodOverride.class);
        plugin.preBeforeTest(TestResult.SUCCESS, MethodOverride.class.getMethod("test"));
        var configuration = started.get(started.size() - 1);
        assertEquals(Browser.CHROME, configuration.getBrowser());
        assertEquals(1280, configuration.getWidth());
        assertEquals(720, configuration.getHeight());
        assertEquals(Lifecycle.RESTART_EVERY_TIME, configuration.getLifecycle());
    }

    @Test
    void inheritedMethodUsesActualClassDeviceSettings() throws Exception {
        lifecycle().preBeforeClass(MobileClass.class);
        plugin.preBeforeTest(TestResult.SUCCESS, MobileClass.class.getMethod("test"));
        assertEquals(1, started.size());
        assertEquals(Browser.CHROME_MOBILE, started.get(0).getBrowser());
        assertEquals(DeviceName.MOBILE_S, started.get(0).getDeviceName());
    }

    @Test
    void failedStartFailsSetupAndAllowsNextAttempt() {
        var attempts = new AtomicInteger();
        var failure = new IllegalStateException("session rejected");
        plugin = new BrowserLifecyclePlugin(this::settings, configuration -> {
            if (attempts.getAndIncrement() == 0) throw failure;
            started.add(configuration);
        }, closed::incrementAndGet, () -> { }, () -> true);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> plugin.preBeforeClass(PlainClass.class)));
        plugin.preBeforeClass(PlainClass.class);
        assertEquals(2, attempts.get());
        assertEquals(1, started.size());
    }

    @Test
    void failedCloseStillClearsLifecycleForNextClass() {
        var failClose = new java.util.concurrent.atomic.AtomicBoolean();
        plugin = new BrowserLifecyclePlugin(this::settings, started::add, () -> {
            if (failClose.getAndSet(false)) throw new IllegalStateException("quit failed");
        }, () -> { }, () -> true);
        plugin.preBeforeClass(PlainClass.class);
        failClose.set(true);
        assertThrows(IllegalStateException.class, () -> plugin.postAfterClass(PlainClass.class));
        plugin.preBeforeClass(PlainClass.class);
        assertEquals(2, started.size());
    }

    @Test
    void failedTestRestartsWhenRequested() throws Exception {
        lifecycle().preBeforeClass(RestartOnFailureClass.class);
        var method = RestartOnFailureClass.class.getMethod("test");
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        plugin.postAfterTest(TestResult.SUCCESS, method, null);
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(1, started.size());
        plugin.postAfterTest(TestResult.FAILURE, method, new AssertionError("failed body"));
        plugin.preBeforeTest(TestResult.SUCCESS, method);
        assertEquals(2, started.size());
    }

    @Test
    void methodDeviceChangesRestartAndRestoreClassConfiguration() throws Exception {
        lifecycle().preBeforeClass(DeviceTransitions.class);
        plugin.preBeforeTest(TestResult.SUCCESS, DeviceTransitions.class.getMethod("phone"));
        plugin.preBeforeTest(TestResult.SUCCESS, DeviceTransitions.class.getMethod("tablet"));
        plugin.preBeforeTest(TestResult.SUCCESS, DeviceTransitions.class.getMethod("test"));
        assertEquals(4, started.size());
        assertEquals(DeviceName.MOBILE_S, started.get(1).getDeviceName());
        assertEquals(DeviceName.TABLET, started.get(2).getDeviceName());
        assertEquals(600, started.get(1).getWidth());
        assertEquals(900, started.get(1).getHeight());
        assertEquals(131, started.get(1).getVersion());
        assertEquals(org.openqa.selenium.Platform.LINUX, started.get(1).getPlatform());
        assertEquals(Browser.CHROME, started.get(3).getBrowser());
        assertEquals(1280, started.get(3).getWidth());
        assertEquals(720, started.get(3).getHeight());
        assertEquals(0, started.get(3).getVersion());
        assertNull(started.get(3).getDeviceName());
    }

    static class PlainClass {
        public void test() { }
    }

    @ExecutionBrowser(lifecycle = Lifecycle.RESTART_EVERY_TIME)
    static class RestartClass extends PlainClass { }

    @ExecutionBrowser(lifecycle = Lifecycle.RESTART_ON_FAIL)
    static class RestartOnFailureClass extends PlainClass { }

    @ExecutionBrowser(browser = Browser.CHROME_MOBILE, deviceName = DeviceName.MOBILE_S, lifecycle = Lifecycle.REUSE_IF_STARTED)
    static class MobileClass extends PlainClass { }

    static class MethodOverride {
        @ExecutionBrowser(lifecycle = Lifecycle.RESTART_EVERY_TIME)
        public void test() { }
    }

    static class DeviceTransitions extends PlainClass {
        @ExecutionBrowser(browser = Browser.CHROME_MOBILE, deviceName = DeviceName.MOBILE_S,
                lifecycle = Lifecycle.REUSE_IF_STARTED, width = 600, height = 900,
                browserVersion = 131, platform = org.openqa.selenium.Platform.LINUX)
        public void phone() { }

        @ExecutionBrowser(browser = Browser.CHROME_MOBILE, deviceName = DeviceName.TABLET,
                lifecycle = Lifecycle.REUSE_IF_STARTED)
        public void tablet() { }
    }
}
