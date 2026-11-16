package solutions.bellatrix.web.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import solutions.bellatrix.web.infrastructure.DriverService;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class BrowserAjaxReadinessTests {
    @AfterEach
    void resetDriver() {
        DriverService.setWrappedDriver(null);
    }

    @Test
    void idleTrackerCompletesImmediately() {
        var calls = new AtomicInteger();
        var browser = browser(() -> { calls.incrementAndGet(); return 0L; });
        assertTrue(browser.waitForAjaxToComplete(Duration.ofSeconds(1), Duration.ZERO));
        assertEquals(1, calls.get());
    }

    @Test
    void activeRequestsMustFinishBeforeReadinessIsConfirmed() {
        var calls = new AtomicInteger();
        var browser = browser(() -> calls.incrementAndGet() < 4 ? 2L : 0L);
        assertTrue(browser.waitForAjaxToComplete(Duration.ofSeconds(1), Duration.ZERO));
        assertEquals(4, calls.get());
    }

    @Test
    void pendingRequestsExhaustBudgetWithoutReportingReadiness() {
        var calls = new AtomicInteger();
        var browser = browser(() -> { calls.incrementAndGet(); return 1L; });
        assertFalse(browser.waitForAjaxToComplete(Duration.ofMillis(30), Duration.ofMillis(10)));
        assertTrue(calls.get() > 1);
    }

    @Test
    void newlyInstalledTrackerCannotConfirmEarlierRequestsCompleted() {
        var browser = browser(() -> null);
        assertFalse(browser.waitForAjaxToComplete(Duration.ofSeconds(1), Duration.ZERO));
    }

    @Test
    void driverFailureKeepsBestEffortWaitNonfatalButDoesNotConfirmReadiness() {
        var browser = browser(() -> { throw new WebDriverException("driver unavailable"); });
        assertFalse(browser.waitForAjaxToComplete(Duration.ofSeconds(1), Duration.ZERO));
    }

    private BrowserService browser(Supplier<Object> response) {
        var driver = (WebDriver) Proxy.newProxyInstance(WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class}, (proxy, method, args) -> {
                    if (method.getName().equals("executeScript")) {
                        return response.get();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DriverService.setWrappedDriver(driver);
        return new BrowserService() {
            @Override
            public void injectInfoNotificationToast(String message) { }

            @Override
            public void injectErrorNotificationToast(String message) { }
        };
    }
}
