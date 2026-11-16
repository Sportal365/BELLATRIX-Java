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

package solutions.bellatrix.core.plugins;

import java.lang.reflect.Method;
import java.util.*;

public final class PluginExecutionEngine {
    private static final LinkedHashSet<Plugin> PLUGINS = new LinkedHashSet<>();
    private static final ThreadLocal<LinkedHashSet<Plugin>> SCOPED_PLUGINS = new ThreadLocal<>();

    public static AutoCloseable openScope() {
        var previous = SCOPED_PLUGINS.get();
        SCOPED_PLUGINS.set(new LinkedHashSet<>(plugins()));
        return () -> {
            if (previous == null) {
                SCOPED_PLUGINS.remove();
            } else {
                SCOPED_PLUGINS.set(previous);
            }
        };
    }

    public static void addPlugin(Plugin plugin) {
        if (plugin != null) {
            plugins().add(plugin);
        }
    }

    public static void removePlugin(Plugin plugin) {
        plugins().remove(plugin);
    }

    public static void preBeforeClass(Class type) {
        for (var plugin : snapshot()) {
            plugin.preBeforeClass(type);
        }
    }

    public static void postBeforeClass(Class type) {
        for (var plugin : snapshot()) {
            plugin.postBeforeClass(type);
        }
    }

    public static void beforeClassFailed(Exception e) {
        notifyAll(plugin -> plugin.beforeClassFailed(e));
    }

    public static void preBeforeTest(TestResult result, Method memberInfo) throws Exception {
        for (var plugin : snapshot()) {
            plugin.preBeforeTest(result, memberInfo);
        }
    }

    public static void postBeforeTest(TestResult result, Method memberInfo) {
        for (var plugin : snapshot()) {
            plugin.postBeforeTest(result, memberInfo);
        }
    }

    public static void beforeTestFailed(Exception e) throws Exception {
        notifyAll(plugin -> plugin.beforeTestFailed(e));
    }

    @Deprecated
    public static void preAfterTest(TestResult result, Method memberInfo) throws Exception {
        notifyTestCleanup(result, null, (plugin, currentResult, failure) -> plugin.preAfterTest(currentResult, memberInfo));
    }

    public static void preAfterTest(TestResult result, TimeRecord timeRecord, Method memberInfo) throws Exception {
        notifyTestCleanup(result, null, (plugin, currentResult, failure) -> plugin.preAfterTest(currentResult, timeRecord, memberInfo));
    }

    @Deprecated
    public static void postAfterTest(TestResult result, Method memberInfo, Throwable failedTestException) {
        notifyTestCleanup(result, failedTestException, (plugin, currentResult, failure) -> plugin.postAfterTest(currentResult, memberInfo, failure));
    }

    public static void postAfterTest(TestResult result, TimeRecord timeRecord, Method memberInfo, Throwable failedTestException) {
        notifyTestCleanup(result, failedTestException, (plugin, currentResult, failure) -> plugin.postAfterTest(currentResult, timeRecord, memberInfo, failure));
    }

    public static void afterTestFailed(Exception e) {
        notifyAll(plugin -> plugin.afterTestFailed(e));
    }

    public static void preAfterClass(Class type) {
        notifyAll(plugin -> plugin.preAfterClass(type));
    }

    public static void postAfterClass(Class type) {
        notifyAll(plugin -> plugin.postAfterClass(type));
    }

    public static void afterClassFailed(Exception e) {
        notifyAll(plugin -> plugin.afterClassFailed(e));
    }

    private static LinkedHashSet<Plugin> plugins() {
        var scoped = SCOPED_PLUGINS.get();
        return scoped == null ? PLUGINS : scoped;
    }

    private static List<Plugin> snapshot() {
        return new ArrayList<>(plugins());
    }

    private static void notifyAll(PluginAction action) {
        var failures = new FailureCollector();
        for (var plugin : snapshot()) {
            failures.run(() -> action.run(plugin));
        }
        failures.throwIfAny();
    }

    private static void notifyTestCleanup(TestResult result, Throwable failedTestException, TestCleanupAction action) {
        var failures = new FailureCollector();
        var testFailures = new FailureCollector(failedTestException);
        for (var plugin : snapshot()) {
            failures.run(() -> action.run(plugin,
                    testFailures.getFailure() == null ? result : TestResult.FAILURE, testFailures.getFailure()));
            testFailures.run(failures::throwIfAny);
        }
        failures.throwIfAny();
    }

    @FunctionalInterface
    private interface PluginAction {
        void run(Plugin plugin) throws Exception;
    }

    @FunctionalInterface
    private interface TestCleanupAction {
        void run(Plugin plugin, TestResult result, Throwable failure) throws Exception;
    }
}
