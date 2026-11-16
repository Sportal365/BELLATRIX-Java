package solutions.bellatrix.web.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NotFoundException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import solutions.bellatrix.web.infrastructure.DriverService;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class BrowserTabWaitTests {
    private final AtomicReference<String> currentTab = new AtomicReference<>();

    @AfterEach
    void resetDriver() {
        DriverService.setWrappedDriver(null);
    }

    @Test
    void defaultWaitAllowsARequestedTabToAppearLater() {
        long start = System.nanoTime();
        var browser = browser(() -> System.nanoTime() - start < Duration.ofMillis(150).toNanos()
                ? Set.of("original") : new LinkedHashSet<>(java.util.List.of("original", "requested")));

        browser.switchToTab(() -> {
            if (!"requested".equals(currentTab.get())) throw new NotFoundException("Wrong tab");
        });

        assertEquals("requested", currentTab.get());
    }

    @Test
    void missingTabFailsAfterTheBoundedWait() {
        var polls = new AtomicInteger();
        var browser = browser(() -> { polls.incrementAndGet(); return Set.of(); });

        var failure = assertThrows(RuntimeException.class, () -> browser.switchToTab(
                () -> fail("There are no tabs"), Duration.ofMillis(30), Duration.ofMillis(5)));

        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(polls.get() > 1);
    }

    @Test
    void unexpectedBrowserFailureIsNotRetriedAsAMissingTab() {
        var failure = new WebDriverException("Disconnected");
        var calls = new AtomicInteger();
        var browser = browser(() -> Set.of("original"));

        var actual = assertThrows(RuntimeException.class, () -> browser.switchToTab(() -> {
            calls.incrementAndGet();
            throw failure;
        }));

        assertSame(failure, actual.getCause());
        assertEquals(1, calls.get());
    }

    private BrowserService browser(Supplier<Set<String>> handles) {
        WebDriver[] driver = new WebDriver[1];
        var locator = (WebDriver.TargetLocator) Proxy.newProxyInstance(WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.TargetLocator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("window")) {
                        currentTab.set((String) args[0]);
                        return driver[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        driver[0] = (WebDriver) Proxy.newProxyInstance(WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class, JavascriptExecutor.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getWindowHandles" -> handles.get();
                        case "switchTo" -> locator;
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        DriverService.setWrappedDriver(driver[0]);
        return new BrowserService();
    }
}
