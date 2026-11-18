package solutions.bellatrix.core.plugins.junit;

import org.junit.jupiter.api.*;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import solutions.bellatrix.core.configuration.ConfigurationService;
import solutions.bellatrix.core.plugins.Plugin;
import solutions.bellatrix.core.plugins.PluginExecutionEngine;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.plugins.TimeRecord;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.EngineFilter.includeEngines;

@Tag("unit")
class BaseTestLifecycleTests {
    private static final List<String> EVENTS = new ArrayList<>();

    @BeforeEach
    void resetEvents() {
        EVENTS.clear();
    }

    @Test
    void usesExactPackagePrivateOverloadedMethodFromJunit() {
        var summary = execute(OverloadedFixture.class);

        assertEquals(2, summary.getTestsSucceededCount());
        assertTrue(EVENTS.contains("before:overloaded:0"));
        assertTrue(EVENTS.contains("before:overloaded:1"));
        assertTrue(EVENTS.contains("after:overloaded:0"));
        assertTrue(EVENTS.contains("after:overloaded:1"));
    }

    @Test
    void setupExceptionFailsTestSkipsBodyAndStillRunsCleanupAndObservers() {
        var summary = execute(SetupFailureFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        assertFailure(summary, "setup failed");
        assertFailure(summary, "diagnostic failed");
        assertFalse(EVENTS.contains("body"));
        assertTrue(EVENTS.containsAll(List.of("failure observer", "cleanup", "post:FAILURE")));
        assertNull(BaseTest.CURRENT_TEST_RESULT.get());
    }

    @Test
    void setupAssertionFailsTestWithoutSkippingCleanup() {
        var summary = execute(SetupAssertionFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        assertFailure(summary, "setup assertion");
        assertFailure(summary, "setup diagnostic");
        assertEquals(List.of("cleanup"), EVENTS);
    }

    @Test
    void classSetupFailureIsNotRetriedAndPreventsEveryBody() {
        var summary = execute(ClassSetupFailureFixture.class);

        assertEquals(2, summary.getTestsFailedCount());
        assertFailure(summary, "class setup failed");
        assertEquals(1, EVENTS.stream().filter("configure"::equals).count());
        assertEquals(1, EVENTS.stream().filter("class setup"::equals).count());
        assertEquals(2, EVENTS.stream().filter("cleanup"::equals).count());
        assertTrue(EVENTS.contains("class cleanup"));
        assertFalse(EVENTS.contains("body"));
    }

    @Test
    void bodyAndCleanupFailuresAreRetainedAndEveryCleanupPhaseRuns() {
        var summary = execute(BodyAndCleanupFailureFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        for (String message : List.of("body failed", "legacy cleanup failed", "modern cleanup failed",
                "fixture cleanup failed", "post cleanup failed")) {
            assertFailure(summary, message);
        }
        assertTrue(EVENTS.containsAll(List.of("legacy survivor", "modern survivor", "fixture cleanup",
                "post survivor", "modern post survivor")));
    }

    @Test
    void postTestPluginFailureTurnsPassingBodyIntoFailedTest() {
        var summary = execute(PostFailureFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        assertEquals(0, summary.getTestsSucceededCount());
        assertFailure(summary, "post cleanup failed");
        assertTrue(EVENTS.contains("post survivor"));
    }

    @Test
    void classCleanupFailureIsReportedAndDoesNotSkipLaterHooks() {
        var summary = execute(ClassCleanupFailureFixture.class);

        assertEquals(1, summary.getTestsSucceededCount());
        assertEquals(1, summary.getContainersFailedCount());
        assertFailure(summary, "pre class cleanup failed");
        assertFailure(summary, "class fixture cleanup failed");
        assertFailure(summary, "post class cleanup failed");
        assertEquals(List.of("pre survivor", "class cleanup", "post survivor"), EVENTS);

        EVENTS.clear();
        assertEquals(1, execute(SecondClassFixture.class).getTestsSucceededCount());
        assertEquals(List.of("second class body"), EVENTS);
    }

    @Test
    void repeatedLaunchesReinitializeClassAndDoNotAccumulatePlugins() {
        assertEquals(1, execute(FirstClassFixture.class).getTestsSucceededCount());
        assertEquals(1, execute(FirstClassFixture.class).getTestsSucceededCount());

        assertEquals(List.of("configure", "first plugin", "first class body", "class cleanup",
                "configure", "first plugin", "first class body", "class cleanup"), EVENTS);
    }

    @Test
    void anotherClassDoesNotInheritClassRegisteredPlugins() {
        assertEquals(1, execute(FirstClassFixture.class).getTestsSucceededCount());
        EVENTS.clear();

        assertEquals(1, execute(SecondClassFixture.class).getTestsSucceededCount());
        assertEquals(List.of("second class body"), EVENTS);
    }

    @Test
    void twoClassesInOneLaunchKeepTheirPluginRegistrationsSeparate() {
        var summary = execute(FirstClassFixture.class, SecondClassFixture.class);

        assertEquals(2, summary.getTestsSucceededCount());
        assertEquals(1, EVENTS.stream().filter("first plugin"::equals).count());
        assertEquals(1, EVENTS.stream().filter("first class body"::equals).count());
        assertEquals(1, EVENTS.stream().filter("second class body"::equals).count());
    }

    @Test
    void pluginConstructorFailurePreventsAnUnconfiguredTestFromPassing() {
        var summary = execute(PluginConstructionFailureFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        var failure = assertInstanceOf(IllegalStateException.class, summary.getFailures().get(0).getException());
        assertEquals("Missing configuration resource: missing-plugin-fixture.properties", failure.getMessage());
        assertFalse(EVENTS.contains("body"));
    }

    @Test
    void intentionallyGlobalPluginsRemainRegisteredOutsideClassScope() {
        Plugin global = new Plugin() {
            @Override
            public void preBeforeTest(TestResult result, Method method) {
                EVENTS.add("global");
            }
        };
        try {
            assertEquals(1, execute(SecondClassFixture.class).getTestsSucceededCount());
            assertEquals(1, execute(SecondClassFixture.class).getTestsSucceededCount());
            assertEquals(List.of("global", "second class body", "global", "second class body"), EVENTS);
        } finally {
            PluginExecutionEngine.removePlugin(global);
        }
    }

    @Test
    void resultResetsBetweenTestsAndPerClassInstanceSemanticsRemain() {
        var summary = execute(ResultResetFixture.class);

        assertEquals(1, summary.getTestsFailedCount());
        assertEquals(1, summary.getTestsSucceededCount());
        assertEquals(List.of("before:SUCCESS", "cleanup:FAILURE", "post:FAILURE", "before:SUCCESS",
                "cleanup:SUCCESS", "post:SUCCESS"), EVENTS);
    }

    @Test
    void disabledTestsDoNotConfigureOrRunResourceHooks() {
        var summary = execute(DisabledFixture.class);

        assertEquals(1, summary.getTestsSkippedCount());
        assertTrue(EVENTS.isEmpty());
    }

    private TestExecutionSummary execute(Class<?>... fixtures) {
        var listener = new SummaryGeneratingListener();
        var launcher = LauncherFactory.create();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(LauncherDiscoveryRequestBuilder.request()
                .selectors(java.util.Arrays.stream(fixtures).map(fixture -> selectClass(fixture)).toList())
                .filters(includeEngines("junit-jupiter"))
                .configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
                .build());
        return listener.getSummary();
    }

    private void assertFailure(TestExecutionSummary summary, String message) {
        assertTrue(summary.getFailures().stream().anyMatch(f -> contains(f.getException(), message)),
                () -> "Missing failure: " + message + " in " + summary.getFailures());
    }

    private boolean contains(Throwable failure, String message) {
        if (String.valueOf(failure.getMessage()).contains(message)) {
            return true;
        }
        if (failure.getCause() != null && contains(failure.getCause(), message)) {
            return true;
        }
        for (var suppressed : failure.getSuppressed()) {
            if (contains(suppressed, message)) {
                return true;
            }
        }
        return false;
    }

    public static class OverloadedFixture extends BaseTest {
        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void preBeforeTest(TestResult result, Method method) {
                    EVENTS.add("before:" + method.getName() + ":" + method.getParameterCount());
                }

                @Override
                public void preAfterTest(TestResult result, TimeRecord record, Method method) {
                    EVENTS.add("after:" + method.getName() + ":" + method.getParameterCount());
                }
            };
        }

        @Test
        void overloaded() {
        }

        @Test
        void overloaded(TestInfo info) {
            assertEquals(1, info.getTestMethod().orElseThrow().getParameterCount());
        }
    }

    public static class SetupFailureFixture extends BaseTest {
        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void beforeTestFailed(Exception failure) {
                    throw new IllegalArgumentException("diagnostic failed");
                }
            };
            new Plugin() {
                @Override
                public void beforeTestFailed(Exception failure) {
                    EVENTS.add("failure observer");
                }

                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    assertTrue(record.getDuration() >= 0);
                    EVENTS.add("post:" + result);
                }
            };
        }

        @Override
        protected void beforeEach() throws Exception {
            throw new IOException("setup failed");
        }

        @Test
        void test() {
            EVENTS.add("body");
        }

        @Override
        protected void afterEach() {
            EVENTS.add("cleanup");
        }
    }

    public static class SetupAssertionFixture extends BaseTest {
        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void beforeTestFailed(Exception failure) throws Exception {
                    failure.addSuppressed(new IllegalStateException("setup diagnostic"));
                    throw failure;
                }
            };
        }

        @Override
        protected void beforeEach() {
            fail("setup assertion");
        }

        @Test
        void test() {
            EVENTS.add("body");
        }

        @Override
        protected void afterEach() {
            EVENTS.add("cleanup");
        }
    }

    public static class ClassSetupFailureFixture extends BaseTest {
        @Override
        protected void configure() {
            EVENTS.add("configure");
        }

        @Override
        protected void beforeAll() throws Exception {
            EVENTS.add("class setup");
            throw new IOException("class setup failed");
        }

        @Test
        void first() {
            EVENTS.add("body");
        }

        @Test
        void second() {
            EVENTS.add("body");
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

    public static class BodyAndCleanupFailureFixture extends BaseTest {
        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void preAfterTest(TestResult result, Method method) {
                    throw new IllegalStateException("legacy cleanup failed");
                }

                @Override
                public void preAfterTest(TestResult result, TimeRecord record, Method method) {
                    fail("modern cleanup failed");
                }

                @Override
                public void postAfterTest(TestResult result, Method method, Throwable failure) {
                    throw new IllegalStateException("post cleanup failed");
                }
            };
            new Plugin() {
                @Override
                public void preAfterTest(TestResult result, Method method) {
                    EVENTS.add("legacy survivor");
                }

                @Override
                public void preAfterTest(TestResult result, TimeRecord record, Method method) {
                    EVENTS.add("modern survivor");
                }

                @Override
                public void postAfterTest(TestResult result, Method method, Throwable failure) {
                    EVENTS.add("post survivor");
                }

                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    EVENTS.add("modern post survivor");
                }
            };
        }

        @Test
        void test() {
            fail("body failed");
        }

        @Override
        protected void afterEach() {
            EVENTS.add("fixture cleanup");
            throw new IllegalStateException("fixture cleanup failed");
        }
    }

    public static class PostFailureFixture extends BaseTest {
        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void postAfterTest(TestResult result, Method method, Throwable failure) {
                    throw new IllegalStateException("post cleanup failed");
                }
            };
            new Plugin() {
                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    EVENTS.add("post survivor");
                }
            };
        }

        @Test
        void test() {
        }
    }

    public static class ClassCleanupFailureFixture extends BaseTest {
        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void preAfterClass(Class type) {
                    throw new IllegalStateException("pre class cleanup failed");
                }

                @Override
                public void postAfterClass(Class type) {
                    fail("post class cleanup failed");
                }
            };
            new Plugin() {
                @Override
                public void preAfterClass(Class type) {
                    EVENTS.add("pre survivor");
                }

                @Override
                public void postAfterClass(Class type) {
                    EVENTS.add("post survivor");
                }
            };
        }

        @Test
        void test() {
        }

        @Override
        protected void afterClass() {
            EVENTS.add("class cleanup");
            throw new IllegalStateException("class fixture cleanup failed");
        }
    }

    public static class FirstClassFixture extends BaseTest {
        @Override
        protected void configure() {
            EVENTS.add("configure");
            new Plugin() {
                @Override
                public void preBeforeTest(TestResult result, Method method) {
                    EVENTS.add("first plugin");
                }
            };
        }

        @Test
        void test() {
            EVENTS.add("first class body");
        }

        @Override
        protected void afterClass() {
            EVENTS.add("class cleanup");
        }
    }

    public static class SecondClassFixture extends BaseTest {
        @Test
        void test() {
            EVENTS.add("second class body");
        }
    }

    public static class PluginConstructionFailureFixture extends BaseTest {
        @Override
        protected void configure() {
            addPlugin(BrokenPlugin.class);
        }

        @Test
        void test() {
            EVENTS.add("body");
        }
    }

    public static class BrokenPlugin extends Plugin {
        public BrokenPlugin() {
            ConfigurationService.getFileAsString("missing-plugin-fixture.properties");
        }
    }

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    public static class ResultResetFixture extends BaseTest {
        private int bodies;

        @Override
        protected void configure() {
            new Plugin() {
                @Override
                public void preBeforeTest(TestResult result, Method method) {
                    EVENTS.add("before:" + result);
                }

                @Override
                public void preAfterTest(TestResult result, TimeRecord record, Method method) {
                    EVENTS.add("cleanup:" + result);
                }

                @Override
                public void postAfterTest(TestResult result, TimeRecord record, Method method, Throwable failure) {
                    EVENTS.add("post:" + result);
                }
            };
        }

        @Test
        @Order(1)
        void first() {
            bodies++;
            fail("first body failed");
        }

        @Test
        @Order(2)
        void second() {
            assertEquals(1, bodies);
        }
    }

    public static class DisabledFixture extends BaseTest {
        @Override
        protected void configure() {
            EVENTS.add("configure");
        }

        @Test
        @Disabled
        void test() {
            EVENTS.add("body");
        }

        @Override
        protected void afterClass() {
            EVENTS.add("class cleanup");
        }
    }
}
