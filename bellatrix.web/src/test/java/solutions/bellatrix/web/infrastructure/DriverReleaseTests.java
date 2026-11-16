package solutions.bellatrix.web.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class DriverReleaseTests {
    private final AtomicInteger quitCalls = new AtomicInteger();

    @AfterEach
    void resetDriver() {
        DriverService.setWrappedDriver(null);
    }

    @Test
    void sessionProbeDetectsCrashedAndDeletedSessions() throws ReflectiveOperationException {
        for (var failure : new WebDriverException[]{new WebDriverException("tab crashed"),
                new NoSuchSessionException("Session was deleted")}) {
            driver(failure);
            assertFalse(DriverService.isSessionUsable());
        }
    }

    @Test
    void healthySessionIsReusable() throws ReflectiveOperationException {
        driver(null);
        assertTrue(DriverService.isSessionUsable());
    }

    @Test
    void missingDriverIsNotReusable() {
        DriverService.setWrappedDriver(null);
        assertFalse(DriverService.isSessionUsable());
    }

    @Test
    void alreadyClosedSessionIsSuccessfulCleanup() throws ReflectiveOperationException {
        driver(new NoSuchSessionException("Already closed"));
        assertDoesNotThrow(DriverService::close);
        assertNull(DriverService.getWrappedDriver());
        assertDoesNotThrow(DriverService::close);
        assertEquals(1, quitCalls.get());
    }

    @Test
    void unexpectedQuitFailureStillFailsAndClearsTheLocalDriver() throws ReflectiveOperationException {
        var failure = new WebDriverException("Grid disconnected");
        driver(failure);
        assertSame(failure, assertThrows(WebDriverException.class, DriverService::close));
        assertNull(DriverService.getWrappedDriver());
        assertDoesNotThrow(DriverService::close);
        assertEquals(1, quitCalls.get());
    }

    @SuppressWarnings("unchecked")
    private void driver(RuntimeException failure) throws ReflectiveOperationException {
        var driver = (WebDriver) Proxy.newProxyInstance(WebDriver.class.getClassLoader(),
                new Class<?>[]{WebDriver.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getCurrentUrl")) {
                        if (failure != null) throw failure;
                        return "https://example.test/";
                    }
                    if (method.getName().equals("quit")) {
                        quitCalls.incrementAndGet();
                        throw failure;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DriverService.setWrappedDriver(driver);
        var disposed = DriverService.class.getDeclaredField("DISPOSED");
        disposed.setAccessible(true);
        ((ThreadLocal<Boolean>) disposed.get(null)).set(false);
    }
}
