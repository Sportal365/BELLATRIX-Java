/*
 * Copyright 2024 Automate The Planet Ltd.
 * Author: Miriam Kyoseva
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.bellatrix.playwright.infrastructure;

import solutions.bellatrix.core.plugins.FailureCollector;
import solutions.bellatrix.core.plugins.Plugin;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.plugins.TimeRecord;
import solutions.bellatrix.playwright.utilities.Settings;

import java.lang.reflect.Method;
import java.util.Objects;

public class BrowserLifecyclePlugin extends Plugin {
    private Class<?> testClass;
    private boolean reuseWithinClass;
    private boolean previousTestPassed;

    @Override
    public void preBeforeClass(Class type) {
        resetClass();
        testClass = type;
        reuseWithinClass = isClassScope(Settings.web().getContextScope());
    }

    @Override
    public void preBeforeTest(TestResult testResult, Method memberInfo) {
        BrowserConfiguration configuration = getBrowserConfiguration(memberInfo);
        boolean reuseContext = previousTestPassed;
        previousTestPassed = false;
        PlaywrightService.startTest(configuration, canRetainContext(configuration), reuseContext);
    }

    @Override
    public void postAfterTest(TestResult testResult, TimeRecord timeRecord, Method memberInfo, Throwable failedTestException) {
        var configuration = PlaywrightService.browserConfiguration();
        if (configuration == null) {
            return;
        }

        boolean failed = testResult == TestResult.FAILURE || failedTestException != null;
        var failures = new FailureCollector();
        if (failed) {
            failures.run(() -> WebScreenshotPlugin.captureFailure(memberInfo));
        }
        var traced = PlaywrightService.wrappedBrowser();
        if (traced != null) {
            failures.run(() -> traced.endTestTrace(failed ? WebScreenshotPlugin.failureTracePath(memberInfo) : null));
        }
        if (failed || !canRetainContext(configuration)) {
            failures.run(PlaywrightService::closeContext);
        }
        var browser = PlaywrightService.wrappedBrowser();
        if (configuration.getLifecycle() == Lifecycle.RESTART_EVERY_TIME
                || (configuration.getLifecycle() == Lifecycle.RESTART_ON_FAIL && failed)
                || failures.getFailure() != null || (browser != null && !browser.getBrowser().isConnected())) {
            failures.run(PlaywrightService::close);
        }
        previousTestPassed = !failed && failures.getFailure() == null;

        if (failedTestException == null) {
            failures.throwIfAny();
        } else {
            new FailureCollector(failedTestException).run(failures::throwIfAny);
        }
    }

    @Override
    public void postAfterClass(Class type) {
        resetClass();
    }

    private void resetClass() {
        testClass = null;
        reuseWithinClass = false;
        previousTestPassed = false;
        PlaywrightService.close();
    }

    private static boolean isClassScope(String contextScope) {
        String scope = Objects.requireNonNullElse(contextScope, "test");
        return switch (scope) {
            case "test" -> false;
            case "class" -> true;
            default -> throw new IllegalArgumentException("webSettings.contextScope must be 'test' or 'class': " + scope);
        };
    }

    private boolean canRetainContext(BrowserConfiguration configuration) {
        return reuseWithinClass && configuration.getLifecycle() != Lifecycle.RESTART_EVERY_TIME;
    }

    private BrowserConfiguration getBrowserConfiguration(Method method) {
        var execution = method.getAnnotation(ExecutionBrowser.class);
        if (execution == null) {
            execution = (testClass == null ? method.getDeclaringClass() : testClass).getAnnotation(ExecutionBrowser.class);
        }

        var testName = method.getDeclaringClass().getName() + "." + method.getName();
        if (execution == null) {
            return new BrowserConfiguration(BrowserTypes.fromText(Settings.web().getDefaultBrowser()),
                    Lifecycle.fromText(Settings.web().getDefaultLifeCycle()), testName);
        }

        var configuration = new BrowserConfiguration(execution.browser(), execution.lifecycle(), testName);
        configuration.setWidth(execution.width());
        configuration.setHeight(execution.height());
        configuration.setVersion(execution.browserVersion());
        configuration.setDeviceName(execution.deviceName());
        return configuration;
    }
}
