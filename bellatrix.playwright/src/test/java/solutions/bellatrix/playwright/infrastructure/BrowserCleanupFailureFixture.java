package solutions.bellatrix.playwright.infrastructure;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import plugins.screenshots.ScreenshotPlugin;
import plugins.screenshots.ScreenshotPluginEventArgs;
import solutions.bellatrix.core.plugins.Plugin;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.playwright.infrastructure.junit.WebTest;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Tag("failure-probe")
@EnabledIfSystemProperty(named = "test.failure.probe", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExecutionBrowser(lifecycle = Lifecycle.RESTART_ON_FAIL)
public class BrowserCleanupFailureFixture extends WebTest {
    private Browser failedBrowser;
    private Page failedPage;
    private Path screenshot;
    private final Consumer<ScreenshotPluginEventArgs> capture = event -> {
        if (event.getFileName().startsWith("BrowserCleanupFailureFixture-failsDuringCleanup-")) {
            assertFalse(failedPage.isClosed());
            screenshot = Path.of(event.getScreenshotPath());
        }
    };

    @Override
    protected void configure() {
        super.configure();
        addPlugin(FailingCleanupPlugin.class);
        ScreenshotPlugin.SCREENSHOT_GENERATED.addListener(capture);
    }

    @Override
    protected void afterClass() {
        ScreenshotPlugin.SCREENSHOT_GENERATED.removeListener(capture);
    }

    @Test
    @Order(1)
    void failsDuringCleanup() {
        failedBrowser = app().browser().context().browser();
        failedPage = app().browser().page();
        failedPage.setContent("<main>Controlled cleanup failure</main>");
    }

    @Test
    @Order(2)
    void previousFailureCapturedPageAndRestartedBrowser() throws Exception {
        assertNotNull(screenshot);
        assertTrue(Files.size(screenshot) > 0);
        assertTrue(failedPage.isClosed());
        assertFalse(failedBrowser.isConnected());
        assertNotSame(failedBrowser, app().browser().context().browser());
    }

    public static class FailingCleanupPlugin extends Plugin {
        @Override
        public void postAfterTest(TestResult result, Method method, Throwable failure) {
            if (method.getName().equals("failsDuringCleanup")) {
                throw new IllegalStateException("controlled browser cleanup failure");
            }
        }
    }
}
