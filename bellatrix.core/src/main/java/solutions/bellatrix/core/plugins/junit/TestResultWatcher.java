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

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import solutions.bellatrix.core.plugins.FailureCollector;
import solutions.bellatrix.core.plugins.PluginExecutionEngine;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.plugins.TimeRecord;

public class TestResultWatcher implements BeforeEachCallback, AfterTestExecutionCallback,
        AfterEachCallback, LifecycleMethodExecutionExceptionHandler {
    @Override
    public void beforeEach(ExtensionContext context) {
        BaseTest.CURRENT_TEST_RESULT.set(TestResult.SUCCESS);
        var timeRecord = new TimeRecord();
        timeRecord.setStartTime(System.currentTimeMillis());
        BaseTest.CURRENT_TEST_TIME_RECORD.set(timeRecord);
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        updateResult(context);
    }

    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        BaseTest.CURRENT_TEST_RESULT.set(TestResult.FAILURE);
        throw throwable;
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
        BaseTest.CURRENT_TEST_RESULT.set(TestResult.FAILURE);
        throw throwable;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        var failures = new FailureCollector();
        try {
            updateResult(context);
            var timeRecord = BaseTest.CURRENT_TEST_TIME_RECORD.get();
            if (timeRecord.getEndTime() == 0) {
                timeRecord.setEndTime(System.currentTimeMillis());
            }
            var method = context.getRequiredTestMethod();
            var testFailures = new FailureCollector(context.getExecutionException().orElse(null));
            failures.run(() -> PluginExecutionEngine.postAfterTest(BaseTest.CURRENT_TEST_RESULT.get(), method, testFailures.getFailure()));
            testFailures.run(failures::throwIfAny);
            if (failures.getFailure() != null) {
                BaseTest.CURRENT_TEST_RESULT.set(TestResult.FAILURE);
            }
            failures.run(() -> PluginExecutionEngine.postAfterTest(BaseTest.CURRENT_TEST_RESULT.get(), timeRecord, method, testFailures.getFailure()));
            testFailures.run(failures::throwIfAny);
            if (failures.getFailure() != null) {
                BaseTest.CURRENT_TEST_RESULT.set(TestResult.FAILURE);
                failures.run(() -> PluginExecutionEngine.afterTestFailed(FailureCollector.asException(testFailures.getFailure())));
            }
        } finally {
            BaseTest.CURRENT_TEST_RESULT.remove();
            BaseTest.CURRENT_TEST_TIME_RECORD.remove();
        }
        failures.throwIfAny();
    }

    private void updateResult(ExtensionContext context) {
        if (context.getExecutionException().isPresent()) {
            BaseTest.CURRENT_TEST_RESULT.set(TestResult.FAILURE);
        }
    }
}
