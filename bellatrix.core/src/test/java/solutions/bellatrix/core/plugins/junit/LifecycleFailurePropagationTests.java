package solutions.bellatrix.core.plugins.junit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import solutions.bellatrix.core.plugins.Plugin;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.plugins.TimeRecord;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

@Tag("unit")
class LifecycleFailurePropagationTests {
    private static final List<String> EVENTS = new ArrayList<>();
    private static final List<Observation> OBSERVATIONS = new ArrayList<>();

    @BeforeEach
    void reset() {
        EVENTS.clear();
        OBSERVATIONS.clear();
    }

    @Test
    void abortedClassSetupAbortsEveryMethodAndStillCleansUp() {
        var summary = execute(AbortedClassFixture.class);

        assertEquals(2, summary.getTestsAbortedCount());
        assertEquals(0, summary.getTestsFailedCount());
        assertEquals(0, summary.getContainersFailedCount());
        assertEquals(List.of("class setup", "cleanup", "cleanup", "class cleanup"), EVENTS);
        assertNull(BaseTest.CURRENT_TEST_RESULT.get());

        EVENTS.clear();
        assertEquals(1, execute(PassingFixture.class).getTestsSucceededCount());
        assertEquals(List.of("passing body"), EVENTS);
    }

    @Test
    void preCleanupFailureUpdatesLaterPluginsAndModernPhaseWithoutLeakingToNextTest() {
        var summary = execute(LegacyPreCleanupFailureFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        assertEquals(1, summary.getTestsSucceededCount());
        assertFailure(summary, "pre cleanup failed");
        assertEquals(List.of("first:legacy:FAILURE", "first:modern:FAILURE", "first:fixture:FAILURE",
                "second:legacy:SUCCESS", "second:modern:SUCCESS", "second:fixture:SUCCESS"), EVENTS);
    }

    @Test
    void modernPreCleanupFailureUpdatesLaterPluginsAndFixtureCleanup() {
        var summary = execute(ModernPreCleanupFailureFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        assertEquals(1, summary.getTestsSucceededCount());
        assertFailure(summary, "pre cleanup failed");
        assertEquals(List.of("first:legacy:SUCCESS", "first:modern:FAILURE", "first:fixture:FAILURE",
                "second:legacy:SUCCESS", "second:modern:SUCCESS", "second:fixture:SUCCESS"), EVENTS);
    }

    @Test
    void legacyPostCleanupFailureReachesLaterLegacyAndModernObservers() {
        assertPostCleanupOutcomes(LegacyPostCleanupFailureFixture.class, TestResult.FAILURE);
    }

    @Test
    void modernPostCleanupFailureReachesLaterModernObservers() {
        assertPostCleanupOutcomes(ModernPostCleanupFailureFixture.class, TestResult.SUCCESS);
    }

    @Test
    void observersRetainOriginalBodyFailureAndAllCleanupDiagnostics() {
        var summary = execute(CombinedPostFailuresFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        for (String message : List.of("body failed", "legacy cleanup failed", "cleanup diagnostic",
                "modern cleanup failed", "failure observer failed")) {
            assertFailure(summary, message);
        }
        assertEquals(3, OBSERVATIONS.size());
        var original = OBSERVATIONS.get(0).failure();
        assertInstanceOf(AssertionError.class, original);
        assertEquals("body failed", original.getMessage());
        assertSame(original, OBSERVATIONS.get(1).failure());
        assertSame(original, OBSERVATIONS.get(2).failure().getCause());
        assertTrue(OBSERVATIONS.stream().allMatch(observation -> observation.result() == TestResult.FAILURE));
        assertTrue(contains(original, "legacy cleanup failed"));
        assertTrue(contains(original, "modern cleanup failed"));
        assertTrue(contains(original, "cleanup diagnostic"));
        assertTrue(contains(original, "failure observer failed"));
    }

    private void assertPostCleanupOutcomes(Class<?> fixture, TestResult legacyResult) {
        var summary = execute(fixture);

        assertEquals(1, summary.getTestsFailedCount());
        assertEquals(1, summary.getTestsSucceededCount());
        assertFailure(summary, "post cleanup failed");
        assertEquals(4, OBSERVATIONS.size());
        assertEquals("first:legacy", OBSERVATIONS.get(0).phase());
        assertEquals(legacyResult, OBSERVATIONS.get(0).result());
        if (legacyResult == TestResult.FAILURE) {
            assertSame(OBSERVATIONS.get(0).failure(), OBSERVATIONS.get(1).failure());
        } else {
            assertNull(OBSERVATIONS.get(0).failure());
        }
        assertEquals("first:modern", OBSERVATIONS.get(1).phase());
        assertEquals(TestResult.FAILURE, OBSERVATIONS.get(1).result());
        assertEquals("post cleanup failed", OBSERVATIONS.get(1).failure().getMessage());
        assertEquals(new Observation("second:legacy", TestResult.SUCCESS, null), OBSERVATIONS.get(2));
        assertEquals(new Observation("second:modern", TestResult.SUCCESS, null), OBSERVATIONS.get(3));
        assertNull(BaseTest.CURRENT_TEST_RESULT.get());
    }

    private static TestExecutionSummary execute(Class<?> fixture) {
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClass(fixture))
                .filters(includeEngines("junit-jupiter"))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
                .build());
        return listener.getSummary();
    }

    private static void assertFailure(TestExecutionSummary summary, String message) {
        assertTrue(summary.getFailures().stream().anyMatch(failure -> contains(failure.getException(), message)),
                () -> "Missing failure: " + message + " in " + summary.getFailures());
    }

    private static boolean contains(Throwable failure, String message) {
        return contains(failure, message, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean contains(Throwable failure, String message, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) {
            return false;
        }
        if (String.valueOf(failure.getMessage()).contains(message) || contains(failure.getCause(), message, visited)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (contains(suppressed, message, visited)) {
                return true;
            }
        }
        return false;
    }

    private record Observation(String phase, TestResult result, Throwable failure) {
    }

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    public abstract static class OrderedFixture extends BaseTest {
        @Test
        @Order(1)
        void first() {
        }

        @Test
        @Order(2)
        void second() {
        }
    }

    public static class AbortedClassFixture extends OrderedFixture {
        @Override
        protected void beforeAll() {
            EVENTS.add("class setup");
            assumeTrue(false, "Required fixture unavailable");
        }

        @Override
        protected void afterEach() {
            EVENTS.add("cleanup");
        }

        @Override
        protected void afterClass() {
            EVENTS.add("class cleanup");
        }
    }

    public static class PassingFixture extends BaseTest {
        @Test
        void test() {
            EVENTS.add("passing body");
        }
    }

    public abstract static class PreCleanupFailureFixture extends OrderedFixture {
        protected abstract boolean failLegacy();

        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void preAfterTest(TestResult result, Method method) {
                    if (failLegacy() && method.getName().equals("first")) {
                        throw new IllegalStateException("pre cleanup failed");
                    }
                }

                @Override
                public void preAfterTest(TestResult result, TimeRecord record, Method method) {
                    if (!failLegacy() && method.getName().equals("first")) {
                        throw new IllegalStateException("pre cleanup failed");
                    }
                }
            };
            new Plugin() {
                @Override
                public void preAfterTest(TestResult result, Method method) {
                    EVENTS.add(method.getName() + ":legacy:" + result);
                }

                @Override
                public void preAfterTest(TestResult result, TimeRecord record, Method method) {
                    EVENTS.add(method.getName() + ":modern:" + result);
                }
            };
        }

        @Override
        protected void afterEach() {
            EVENTS.add(getTestName() + ":fixture:" + CURRENT_TEST_RESULT.get());
        }
    }

    public static class LegacyPreCleanupFailureFixture extends PreCleanupFailureFixture {
        @Override
        protected boolean failLegacy() {
            return true;
        }
    }

    public static class ModernPreCleanupFailureFixture extends PreCleanupFailureFixture {
        @Override
        protected boolean failLegacy() {
            return false;
        }
    }

    public abstract static class PostCleanupFailureFixture extends OrderedFixture {
        protected abstract boolean failLegacy();

        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void postAfterTest(TestResult result, Method method, Throwable failure) {
                    if (failLegacy() && method.getName().equals("first")) {
                        throw new IllegalStateException("post cleanup failed");
                    }
                }

                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    if (!failLegacy() && method.getName().equals("first")) {
                        throw new IllegalStateException("post cleanup failed");
                    }
                }
            };
            new Plugin() {
                @Override
                public void postAfterTest(TestResult result, Method method, Throwable failure) {
                    OBSERVATIONS.add(new Observation(method.getName() + ":legacy", result, failure));
                }

                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    OBSERVATIONS.add(new Observation(method.getName() + ":modern", result, failure));
                }
            };
        }
    }

    public static class LegacyPostCleanupFailureFixture extends PostCleanupFailureFixture {
        @Override
        protected boolean failLegacy() {
            return true;
        }
    }

    public static class ModernPostCleanupFailureFixture extends PostCleanupFailureFixture {
        @Override
        protected boolean failLegacy() {
            return false;
        }
    }

    public static class CombinedPostFailuresFixture extends BaseTest {
        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void postAfterTest(TestResult result, Method method, Throwable failure) {
                    var cleanup = new IllegalStateException("legacy cleanup failed");
                    cleanup.addSuppressed(new IllegalArgumentException("cleanup diagnostic"));
                    throw cleanup;
                }

                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    throw new IllegalStateException("modern cleanup failed");
                }

                @Override
                public void afterTestFailed(Exception failure) {
                    throw new IllegalStateException("failure observer failed");
                }
            };
            new Plugin() {
                @Override
                public void postAfterTest(TestResult result, Method method, Throwable failure) {
                    OBSERVATIONS.add(new Observation("legacy", result, failure));
                }

                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    OBSERVATIONS.add(new Observation("modern", result, failure));
                }

                @Override
                public void afterTestFailed(Exception failure) {
                    OBSERVATIONS.add(new Observation("failure observer", TestResult.FAILURE, failure));
                }
            };
        }

        @Test
        void test() {
            throw new AssertionError("body failed");
        }
    }
}
