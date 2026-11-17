package solutions.bellatrix.core.utilities;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class WaitTests {
    @Test
    void optionalRetryReportsSuccessAfterTransientFailure() {
        AtomicInteger attempts = new AtomicInteger();
        assertTrue(Wait.tryRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("Not ready");
            }
        }, 5, 0, IllegalStateException.class));
        assertEquals(3, attempts.get());
    }

    @Test
    void optionalRetryReturnsFalseAfterExhaustingExpectedFailures() {
        AtomicInteger attempts = new AtomicInteger();
        assertFalse(Wait.tryRetry(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Not ready");
        }, 2, 0, IllegalStateException.class));
        assertEquals(3, attempts.get());
    }

    @Test
    void optionalRetryStillThrowsUnexpectedFailuresImmediately() {
        AtomicInteger attempts = new AtomicInteger();
        var failure = new IllegalArgumentException("Invalid input");
        var thrown = assertThrows(RuntimeException.class, () -> Wait.tryRetry(() -> {
            attempts.incrementAndGet();
            throw failure;
        }, 5, 0, IllegalStateException.class));
        assertSame(failure, thrown.getCause());
        assertEquals(1, attempts.get());
    }

    @Test
    void optionalRetryDoesNotSwallowInterruption() {
        try {
            Thread.currentThread().interrupt();
            var thrown = assertThrows(RuntimeException.class, () -> Wait.tryRetry(() -> {
                throw new IllegalStateException("Not ready");
            }, 5, 1, IllegalStateException.class));
            assertInstanceOf(InterruptedException.class, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void defaultRetryUsesOneSecondPolling() {
        AtomicInteger attempts = new AtomicInteger();

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> Wait.retry(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Not ready");
            }
        }, IllegalStateException.class));

        assertEquals(2, attempts.get());
    }

    @Test
    void countRetryStopsAfterSuccess() {
        AtomicInteger attempts = new AtomicInteger();

        Wait.retry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("Not ready");
            }
        }, 5, 0, IllegalStateException.class);

        assertEquals(3, attempts.get());
    }

    @Test
    void countRetryThrowsLastIgnoredFailureAfterInitialAttemptAndRetries() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("Not ready");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Wait.retry(() -> {
            attempts.incrementAndGet();
            throw failure;
        }, 3, 0, IllegalStateException.class));

        assertEquals(4, attempts.get());
        assertSame(failure, thrown.getCause());
    }

    @Test
    void zeroRetriesStillMakesOneAttemptWithoutSleepingAfterFailure() {
        AtomicInteger attempts = new AtomicInteger();

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertThrows(RuntimeException.class,
                () -> Wait.retry(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("Not ready");
                }, 0, 10, IllegalStateException.class)));

        assertEquals(1, attempts.get());
    }

    @Test
    void countRetryCanSuppressExhaustedIgnoredFailures() {
        AtomicInteger attempts = new AtomicInteger();

        assertDoesNotThrow(() -> Wait.retry(() -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Not ready");
        }, 2, 0, false, IllegalStateException.class));

        assertEquals(3, attempts.get());
    }

    @Test
    void countRetryDoesNotRetryUnexpectedFailures() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalArgumentException failure = new IllegalArgumentException("Invalid input");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> Wait.retry(() -> {
            attempts.incrementAndGet();
            throw failure;
        }, 5, 0, IllegalStateException.class));

        assertEquals(1, attempts.get());
        assertSame(failure, thrown.getCause());
    }

    @Test
    void countRetryCanSuppressUnexpectedFailuresWithoutRetrying() {
        AtomicInteger attempts = new AtomicInteger();

        assertDoesNotThrow(() -> Wait.retry(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Invalid input");
        }, 5, 0, false, IllegalStateException.class));

        assertEquals(1, attempts.get());
    }

    @Test
    void durationRetrySucceedsAfterIgnoredFailure() {
        AtomicInteger attempts = new AtomicInteger();

        assertTrue(Wait.retry(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Not ready");
            }
        }, Duration.ofSeconds(1), Duration.ZERO, true, IllegalStateException.class));

        assertEquals(2, attempts.get());
    }

    @Test
    void durationRetryThrowsIgnoredFailureOnTimeoutAndCapsSleep() {
        IllegalStateException failure = new IllegalStateException("Not ready");

        // The second sleep is capped at the 100 ms left: about 1 s in all, 1.8 s if the sleep were not capped.
        RuntimeException thrown = assertTimeoutPreemptively(Duration.ofMillis(1500),
                () -> assertThrows(RuntimeException.class, () -> Wait.retry(() -> {
                    throw failure;
                }, Duration.ofMillis(1000), Duration.ofMillis(900), true, IllegalStateException.class)));

        assertSame(failure, thrown.getCause());
    }

    @Test
    void durationRetryCanReturnFalseOnTimeout() {
        assertFalse(Wait.retry(() -> {
            throw new IllegalStateException("Not ready");
        }, Duration.ofMillis(20), Duration.ofMillis(1), false, IllegalStateException.class));
    }

    @Test
    void durationRetryThrowsUnexpectedFailureWithoutWaiting() {
        AtomicInteger attempts = new AtomicInteger();
        IllegalArgumentException failure = new IllegalArgumentException("Invalid input");

        RuntimeException thrown = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> assertThrows(RuntimeException.class, () -> Wait.retry(() -> {
                    attempts.incrementAndGet();
                    throw failure;
                }, Duration.ofSeconds(30), Duration.ofSeconds(10), true, IllegalStateException.class)));

        assertEquals(1, attempts.get());
        assertSame(failure, thrown.getCause());
    }

    @Test
    void durationRetryCanSuppressUnexpectedFailureWithoutWaiting() {
        AtomicInteger attempts = new AtomicInteger();

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertFalse(Wait.retry(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("Invalid input");
        }, Duration.ofSeconds(30), Duration.ofSeconds(10), false, IllegalStateException.class)));

        assertEquals(1, attempts.get());
    }

    @Test
    void durationOverloadWithoutBooleanTakesTimeoutThenSleep() {
        AtomicInteger attempts = new AtomicInteger();

        Wait.retry(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Not ready");
            }
        }, Duration.ofSeconds(1), Duration.ofMillis(1), IllegalStateException.class);

        assertEquals(2, attempts.get());
    }

    @Test
    void interruptionStopsRetryAndPreservesInterruptStatus() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        Thread thread = new Thread(() -> {
            RuntimeException thrown = assertThrows(RuntimeException.class, () -> Wait.retry(() -> {
                attempts.incrementAndGet();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Not ready");
            }, 3, 10, IllegalStateException.class));
            assertInstanceOf(InterruptedException.class, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        });
        java.util.concurrent.atomic.AtomicReference<Throwable> uncaught = new java.util.concurrent.atomic.AtomicReference<>();
        thread.setUncaughtExceptionHandler((ignored, failure) -> uncaught.set(failure));

        thread.start();
        thread.join(1000);

        assertFalse(thread.isAlive());
        assertNull(uncaught.get());
        assertEquals(1, attempts.get());
    }

    @Test
    void conditionWaitReturnsTrue_when_conditionBecomesTrueBeforeTimeout() {
        AtomicInteger evaluations = new AtomicInteger();

        assertTrue(Wait.forConditionUntilTimeout(() -> evaluations.incrementAndGet() == 3, 1_000, 1));

        assertEquals(3, evaluations.get());
    }

    @Test
    void conditionWaitSleepsThePollingIntervalBetweenAttempts_when_conditionThrows() {
        AtomicInteger evaluations = new AtomicInteger();

        boolean met = assertTimeout(Duration.ofSeconds(2), () -> Wait.forConditionUntilTimeout(() -> {
            evaluations.incrementAndGet();
            throw new IllegalStateException("Not ready");
        }, 500, 100));

        assertFalse(met);
        assertTrue(evaluations.get() <= 8, () -> "Condition evaluated " + evaluations.get() + " times in 500 ms");
    }

    @Test
    void conditionWaitStopsAndKeepsTheInterruptFlag_when_threadIsInterrupted() {
        AtomicInteger evaluations = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            RuntimeException thrown = assertTimeout(Duration.ofSeconds(1), () -> assertThrows(RuntimeException.class,
                    () -> Wait.forConditionUntilTimeout(() -> evaluations.incrementAndGet() < 0, 30_000, 100)));
            assertInstanceOf(InterruptedException.class, thrown.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, evaluations.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidRetryArgumentsFailBeforeExecutingAction() {
        Runnable unexpectedAction = () -> fail("Action should not run");

        assertThrows(IllegalArgumentException.class, () -> Wait.retry(unexpectedAction, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> Wait.retry(unexpectedAction, 1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> Wait.retry(unexpectedAction, Duration.ZERO, Duration.ZERO, true));
        assertThrows(IllegalArgumentException.class,
                () -> Wait.retry(unexpectedAction, Duration.ofSeconds(1), Duration.ofMillis(-1), true));
        assertThrows(IllegalArgumentException.class,
                () -> Wait.retry(unexpectedAction, Duration.ofMillis(1), Duration.ofSeconds(1), true));
        assertThrows(IllegalArgumentException.class,
                () -> Wait.retry(unexpectedAction, Duration.ofMillis(1), Duration.ofSeconds(1), IllegalStateException.class));
    }
}
