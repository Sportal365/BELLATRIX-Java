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

package solutions.bellatrix.web.infrastructure;

import org.openqa.selenium.WebDriverException;
import solutions.bellatrix.core.configuration.ConfigurationService;
import solutions.bellatrix.core.plugins.Plugin;
import solutions.bellatrix.core.plugins.TestResult;
import solutions.bellatrix.core.utilities.SecretsResolver;
import solutions.bellatrix.core.utilities.Log;
import solutions.bellatrix.web.configuration.WebSettings;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public class BrowserLifecyclePlugin extends Plugin {
    private final Supplier<WebSettings> settings;
    private final Consumer<BrowserConfiguration> startDriver;
    private final Runnable closeDriver;
    private final Runnable compactCapture;
    private final BooleanSupplier sessionUsable;
    private Class<?> currentClass;

    public BrowserLifecyclePlugin() {
        this(() -> ConfigurationService.get(WebSettings.class), DriverService::start, DriverService::close,
                ProxyServer::compactResponseBodies, DriverService::isSessionUsable);
    }

    BrowserLifecyclePlugin(Supplier<WebSettings> settings, Consumer<BrowserConfiguration> startDriver,
                           Runnable closeDriver, Runnable compactCapture, BooleanSupplier sessionUsable) {
        this.settings = settings;
        this.startDriver = startDriver;
        this.closeDriver = closeDriver;
        this.compactCapture = compactCapture;
        this.sessionUsable = sessionUsable;
    }

    private static final ThreadLocal<BrowserConfiguration> CURRENT_BROWSER_CONFIGURATION;
    private static final ThreadLocal<BrowserConfiguration> PREVIOUS_BROWSER_CONFIGURATION;
    private static final ThreadLocal<Boolean> IS_BROWSER_STARTED_CORRECTLY;

    static {
        CURRENT_BROWSER_CONFIGURATION = new ThreadLocal<>();
        PREVIOUS_BROWSER_CONFIGURATION = new ThreadLocal<>();
        IS_BROWSER_STARTED_CORRECTLY = ThreadLocal.withInitial(() -> false);
    }

    @Override
    public void preBeforeClass(Class type) {
        currentClass = type;
        var executionType = settings.get().getExecutionType();
        if (Objects.equals(executionType, "regular") || Objects.equals(executionType, "grid")) {
            CURRENT_BROWSER_CONFIGURATION.set(getBrowserConfiguration(type));
            if (shouldRestartBrowser()) {
                shutdownBrowser();
                startBrowser();
            }
        }
        super.preBeforeClass(type);
    }

    @Override
    public void postAfterClass(Class type) {
        try {
            shutdownBrowser();
        } finally {
            CURRENT_BROWSER_CONFIGURATION.remove();
            currentClass = null;
        }
        super.postAfterClass(type);
    }

    @Override
    public void preBeforeTest(TestResult testResult, Method memberInfo) {
        CURRENT_BROWSER_CONFIGURATION.set(getBrowserConfiguration(memberInfo));
        if (shouldRestartBrowser()) {
            shutdownBrowser();
            startBrowser();
        } else if (!sessionUsable.getAsBoolean()) {
            Log.info("Replacing an unusable browser session before the next test");
            try {
                shutdownBrowser();
            } catch (WebDriverException exception) {
                Log.error("Unusable browser session could not be closed: " + exception.getClass().getSimpleName());
            }
            startBrowser();
        }
        if (Boolean.TRUE.equals(settings.get().getShouldCaptureHttpTraffic())) {
            compactCapture.run();
        }
    }

    @Override
    public void beforeTestFailed(Exception ex) throws Exception {
        throw ex;
    }

    @Override
    public void postAfterTest(TestResult testResult, Method memberInfo, Throwable failedTestException) {
        var configuration = CURRENT_BROWSER_CONFIGURATION.get();
        if (configuration == null) {
            return;
        }
        if (configuration.getLifecycle() == Lifecycle.REUSE_IF_STARTED) {
           return;
        }

        if (configuration.getLifecycle() ==
                Lifecycle.RESTART_ON_FAIL && testResult != TestResult.FAILURE ) {
            return;
        }

        shutdownBrowser();
    }

    private void shutdownBrowser() {
        try {
            closeDriver.run();
        } finally {
            PREVIOUS_BROWSER_CONFIGURATION.remove();
            IS_BROWSER_STARTED_CORRECTLY.remove();
        }
    }

    private void startBrowser() {
        try {
            startDriver.accept(CURRENT_BROWSER_CONFIGURATION.get());
            IS_BROWSER_STARTED_CORRECTLY.set(true);
            PREVIOUS_BROWSER_CONFIGURATION.set(CURRENT_BROWSER_CONFIGURATION.get());
        } catch (RuntimeException | Error ex) {
            IS_BROWSER_STARTED_CORRECTLY.set(false);
            PREVIOUS_BROWSER_CONFIGURATION.remove();
            throw ex;
        }
    }

    private boolean shouldRestartBrowser() {
        var previousConfiguration = PREVIOUS_BROWSER_CONFIGURATION.get();
        var currentConfiguration = CURRENT_BROWSER_CONFIGURATION.get();
        if (previousConfiguration == null) {
            return true;
        } else if (!IS_BROWSER_STARTED_CORRECTLY.get()) {
            return true;
        } else if (!previousConfiguration.equals(currentConfiguration)) {
            return true;
        } else {
            return false;
        }
    }

    private BrowserConfiguration getBrowserConfiguration(Method memberInfo) {
        BrowserConfiguration result;
        var classBrowserType = getExecutionBrowserClassLevel(currentClass == null ? memberInfo.getDeclaringClass() : currentClass);
        var annotation = memberInfo.getDeclaredAnnotation(ExecutionBrowser.class);
        result = annotation == null ? classBrowserType : applyAnnotation(classBrowserType, annotation);
        String testFullName = String.format("%s.%s", memberInfo.getDeclaringClass().getName(), memberInfo.getName());
        result.setTestName(testFullName);
        return result;
    }

    private BrowserConfiguration getBrowserConfiguration(Type classType) {
        return getExecutionBrowserClassLevel((Class<?>)classType);
    }

    private BrowserConfiguration getExecutionBrowserClassLevel(Class<?> clazz) {
        var defaults = settings.get();
        var browser = Browser.fromText(SecretsResolver.getSecret(defaults.getDefaultBrowser()));
        var lifecycle = Lifecycle.fromText(defaults.getDefaultLifeCycle());
        var configuration = new BrowserConfiguration(browser, lifecycle, defaults.getDefaultBrowserWidth(), defaults.getDefaultBrowserHeight());
        var annotation = clazz.getDeclaredAnnotation(ExecutionBrowser.class);
        if (annotation != null) {
            applyAnnotation(configuration, annotation);
        }
        configuration.setTestName(clazz.getName());
        return configuration;
    }

    private BrowserConfiguration applyAnnotation(BrowserConfiguration configuration, ExecutionBrowser annotation) {
        if (annotation.browser() != Browser.NOT_SET) configuration.setBrowser(annotation.browser());
        if (annotation.deviceName() != DeviceName.NOT_SET) configuration.setDeviceName(annotation.deviceName());
        if (annotation.width() != 0) configuration.setWidth(annotation.width());
        if (annotation.height() != 0) configuration.setHeight(annotation.height());
        if (annotation.browserVersion() != 0) configuration.setVersion(annotation.browserVersion());
        if (annotation.platform() != org.openqa.selenium.Platform.ANY) configuration.setPlatform(annotation.platform());
        configuration.setLifecycle(annotation.lifecycle());
        return configuration;
    }
}
