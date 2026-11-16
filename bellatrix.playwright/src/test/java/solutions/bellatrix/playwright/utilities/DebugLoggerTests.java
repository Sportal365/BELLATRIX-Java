package solutions.bellatrix.playwright.utilities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class DebugLoggerTests {
    @Test
    void retainsTheAssertionCauseAndIncludesTheComponentDiagnostic() {
        var original = new AssertionFailedError("Condition was false");
        String diagnostic = "Button #save should be visible but was hidden";
        var failure = assertThrows(AssertionFailedError.class,
                () -> DebugLogger.assertAndLogOnFail(() -> { throw original; }, diagnostic));

        assertEquals(diagnostic, failure.getMessage());
        assertSame(original, failure.getCause());
    }
}
