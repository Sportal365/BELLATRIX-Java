/*
 * Copyright 2022 Automate The Planet Ltd.
 * Author: Anton Angelov
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.bellatrix.core.plugins.junit;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import solutions.bellatrix.core.plugins.FailureCollector;
import solutions.bellatrix.core.plugins.PluginExecutionEngine;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.plugins.TimeRecord;
import solutions.bellatrix.core.plugins.UsesPlugins;

@ExtendWith(TestResultWatcher.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(TestDurationWatcher.class)
public class BaseTest extends UsesPlugins {
    static final ThreadLocal<TestResult> CURRENT_TEST_RESULT = new ThreadLocal<>();
    static final ThreadLocal<TimeRecord> CURRENT_TEST_TIME_RECORD = ThreadLocal.withInitial(TimeRecord::new);
    private boolean classInitializationAttempted;
    private Throwable classInitializationFailure;
    private AutoCloseable pluginScope;
    private TestInfo testInfo;

    @BeforeEach
    public void beforeMethodCore(TestInfo testInfo) throws Exception {
        this.testInfo = testInfo;
        try {
            beforeClassCore();
            var methodInfo = testInfo.getTestMethod().orElseThrow();
            PluginExecutionEngine.preBeforeTest(CURRENT_TEST_RESULT.get(), methodInfo);
            beforeEach();
            PluginExecutionEngine.postBeforeTest(CURRENT_TEST_RESULT.get(), methodInfo);
        } catch (Throwable failure) {
            CURRENT_TEST_RESULT.set(TestResult.FAILURE);
            var failures = new FailureCollector(failure);
            failures.run(this::onBeforeEachFailure);
            failures.run(() -> PluginExecutionEngine.beforeTestFailed(FailureCollector.asException(failure)));
            failures.throwIfAny();
        }
    }

    public void beforeClassCore() {
        if (classInitializationAttempted) {
            if (classInitializationFailure != null) {
                new FailureCollector(classInitializationFailure).throwIfAny();
            }
            return;
        }
        classInitializationAttempted = true;
        pluginScope = PluginExecutionEngine.openScope();
        try {
            configure();
            var testClass = this.getClass();
            PluginExecutionEngine.preBeforeClass(testClass);
            beforeAll();
            PluginExecutionEngine.postBeforeClass(testClass);
        } catch (Throwable failure) {
            classInitializationFailure = failure;
            var failures = new FailureCollector(failure);
            failures.run(() -> PluginExecutionEngine.beforeClassFailed(FailureCollector.asException(failure)));
            failures.throwIfAny();
        }
    }

    @AfterEach
    public void afterMethodCore(TestInfo testInfo) {
        var methodInfo = testInfo.getTestMethod().orElseThrow();
        var failures = new FailureCollector();
        failures.run(() -> PluginExecutionEngine.preAfterTest(CURRENT_TEST_RESULT.get(), methodInfo));
        if (failures.getFailure() != null) {
            CURRENT_TEST_RESULT.set(TestResult.FAILURE);
        }
        failures.run(() -> PluginExecutionEngine.preAfterTest(CURRENT_TEST_RESULT.get(), CURRENT_TEST_TIME_RECORD.get(), methodInfo));
        if (failures.getFailure() != null) {
            CURRENT_TEST_RESULT.set(TestResult.FAILURE);
        }
        failures.run(this::afterEach);
        if (failures.getFailure() != null) {
            CURRENT_TEST_RESULT.set(TestResult.FAILURE);
            failures.run(() -> PluginExecutionEngine.afterTestFailed(FailureCollector.asException(failures.getFailure())));
        }
        failures.throwIfAny();
    }

    @AfterAll
    public void afterClassCore(TestInfo testInfo) {
        if (!classInitializationAttempted) {
            return;
        }
        var failures = new FailureCollector();
        try {
            var testClass = testInfo.getTestClass().orElseThrow();
            failures.run(() -> PluginExecutionEngine.preAfterClass(testClass));
            failures.run(this::afterClass);
            failures.run(() -> PluginExecutionEngine.postAfterClass(testClass));
            if (failures.getFailure() != null) {
                failures.run(() -> PluginExecutionEngine.afterClassFailed(FailureCollector.asException(failures.getFailure())));
            }
        } finally {
            if (pluginScope != null) {
                failures.run(pluginScope::close);
                pluginScope = null;
            }
            CURRENT_TEST_RESULT.remove();
            CURRENT_TEST_TIME_RECORD.remove();
        }
        failures.throwIfAny();
    }

    protected String getTestName() {
        return this.testInfo.getTestMethod().orElseThrow().getName();
    }

    protected void configure() {
    }

    protected void beforeAll() throws Exception {
    }

    protected void beforeEach() throws Exception {
    }

    protected void onBeforeEachFailure() {
    }

    protected void afterEach() {
    }

    protected void afterClass() {
    }
}
