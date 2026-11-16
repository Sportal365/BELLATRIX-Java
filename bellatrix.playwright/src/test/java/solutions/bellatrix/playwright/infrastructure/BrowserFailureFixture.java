package solutions.bellatrix.playwright.infrastructure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import solutions.bellatrix.playwright.infrastructure.junit.WebTest;

import static org.junit.jupiter.api.Assertions.fail;

@Tag("failure-probe")
@EnabledIfSystemProperty(named = "test.failure.probe", matches = "true")
class BrowserFailureFixture extends WebTest {
    @Test
    void reportsOriginalFailureAndCapturesPage() {
        app().browser().page().setContent("<main>Controlled browser failure</main>");
        fail("controlled browser assertion failure");
    }
}
