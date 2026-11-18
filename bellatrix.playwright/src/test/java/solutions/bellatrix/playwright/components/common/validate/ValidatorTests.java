package solutions.bellatrix.playwright.components.common.validate;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class ValidatorTests {
    @Test
    void validationReturnsWellUnderOneSecond_when_conditionBecomesTrueAfter300Milliseconds() {
        long readyAt = System.nanoTime() + Duration.ofMillis(300).toNanos();
        long started = System.nanoTime();

        Validator.validateCondition(() -> System.nanoTime() >= readyAt);

        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(Duration.ofMillis(700)) < 0, elapsed::toString);
    }

    @Test
    void failureValueIsNotRead_when_validationPasses() {
        AtomicInteger reads = new AtomicInteger();

        Validator.validate(() -> { }, null, "inner text", "Ready", () -> {
            reads.incrementAndGet();
            return "Ready";
        }, "be");

        assertEquals(0, reads.get());
    }

    @Test
    void lastObservedReturnsTheLatestValueWithoutAnotherRead_when_valueWasRead() {
        AtomicInteger reads = new AtomicInteger();
        ObservedSupplier<Integer> observed = new ObservedSupplier<>(reads::incrementAndGet);

        observed.get();
        observed.get();

        assertEquals(2, observed.lastObserved());
        assertEquals(2, reads.get());
    }

    @Test
    void lastObservedRethrowsTheReadFailure_when_noValueWasEverRead() {
        IllegalStateException failure = new IllegalStateException("Element is not attached");
        ObservedSupplier<String> observed = new ObservedSupplier<>(() -> {
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, observed::get));
        assertSame(failure, assertThrows(IllegalStateException.class, observed::lastObserved));
    }
}
