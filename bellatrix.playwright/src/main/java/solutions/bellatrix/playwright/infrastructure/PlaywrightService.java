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

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import solutions.bellatrix.core.plugins.FailureCollector;
import solutions.bellatrix.playwright.components.shadowdom.ShadowDomService;
import solutions.bellatrix.playwright.configuration.TimeoutSettings;
import solutions.bellatrix.playwright.configuration.WebSettings;
import solutions.bellatrix.playwright.utilities.Settings;

import java.time.ZoneId;
import java.util.Objects;

public final class PlaywrightService {
    private static final ThreadLocal<BrowserSession> SESSION = new ThreadLocal<>();

    private PlaywrightService() {
    }

    public static WrappedBrowser start(BrowserConfiguration configuration) {
        return start(configuration, false, false, null);
    }

    static WrappedBrowser startTest(BrowserConfiguration configuration, boolean retainContext, boolean reuseContext) {
        return start(configuration, retainContext, reuseContext, null);
    }

    private static WrappedBrowser start(BrowserConfiguration configuration, boolean retainContext, boolean reuseContext,
                                        String storageState) {
        Objects.requireNonNull(configuration, "Browser configuration is required");
        var settings = Settings.web();
        var executionType = settings.getExecutionType();
        if (executionType != null && !executionType.equalsIgnoreCase("regular")
                && !executionType.equalsIgnoreCase("local")) {
            throw new IllegalArgumentException("Unsupported browser execution type: " + executionType);
        }

        var launchOptions = launchOptions(configuration, settings);
        var existing = SESSION.get();
        if (existing != null && (!existing.browser().getBrowser().isConnected()
                || !existing.launchOptions().equals(launchOptions))) {
            close();
            existing = null;
        }

        try {
            var browser = existing == null ? launch(launchOptions) : existing.browser();
            SESSION.set(new BrowserSession(browser, configuration, launchOptions, retainContext));
            if (reuseContext && retainContext && existing != null && existing.retainContext()
                    && configuration.equals(existing.configuration()) && browser.hasOpenContext()) {
                browser.prepareNextTest();
            } else {
                browser.closeContext();
                browser.openContext(contextOptions(configuration, settings, storageState), settings);
            }
            return browser;
        } catch (RuntimeException | Error failure) {
            var failures = new FailureCollector(failure);
            failures.run(PlaywrightService::close);
            failures.throwIfAny();
            throw failure;
        }
    }

    public static void closeContext() {
        var browser = wrappedBrowser();
        if (browser != null) {
            browser.closeContext();
        }
    }

    public static void close() {
        var browser = wrappedBrowser();
        SESSION.remove();
        if (browser != null) {
            browser.close();
        }
    }

    public static void restartBrowserContext() {
        restartBrowserContext(null);
    }

    /**
     * Replaces the current test's context with a new one that starts from a Playwright storage state
     * (cookies and localStorage, as returned by {@link BrowserContext#storageState()}); null starts it empty.
     * With class scope the new context is kept for the following tests of the class like the one it replaces.
     */
    public static void restartBrowserContext(String storageState) {
        BrowserSession session = Objects.requireNonNull(SESSION.get(), "No browser is running");
        start(session.configuration(), session.retainContext(), false, storageState);
    }

    public static BrowserConfiguration browserConfiguration() {
        var session = SESSION.get();
        return session == null ? null : session.configuration();
    }

    public static WrappedBrowser wrappedBrowser() {
        var session = SESSION.get();
        return session == null ? null : session.browser();
    }

    private static WrappedBrowser launch(LaunchOptions launch) {
        var playwright = Playwright.create();
        try {
            ShadowDomService.registerSelectors(playwright);
            var type = launch.type();
            var browserType = switch (type) {
                case CHROMIUM, CHROMIUM_HEADLESS, EDGE, EDGE_HEADLESS -> playwright.chromium();
                case FIREFOX, FIREFOX_HEADLESS -> playwright.firefox();
                case WEBKIT, WEBKIT_HEADLESS -> playwright.webkit();
                default -> throw new IllegalArgumentException("Unsupported browser: " + type);
            };
            var options = new BrowserType.LaunchOptions().setHeadless(launch.headless());
            if (type == BrowserTypes.EDGE || type == BrowserTypes.EDGE_HEADLESS) {
                options.setChannel("msedge");
            }
            return new WrappedBrowser(playwright, browserType.launch(options));
        } catch (RuntimeException | Error failure) {
            var failures = new FailureCollector(failure);
            failures.run(playwright::close);
            failures.throwIfAny();
            throw failure;
        }
    }

    private static LaunchOptions launchOptions(BrowserConfiguration configuration, WebSettings settings) {
        var type = switch (configuration.getBrowserTypes()) {
            case CHROME, CHROME_HEADLESS, CHROME_MOBILE -> BrowserTypes.CHROMIUM_HEADLESS;
            default -> configuration.getBrowserTypes();
        };
        boolean headlessByType = type != BrowserTypes.CHROMIUM && type != BrowserTypes.EDGE
                && type != BrowserTypes.FIREFOX && type != BrowserTypes.WEBKIT;
        return new LaunchOptions(type, Objects.requireNonNullElse(settings.getHeadless(), headlessByType));
    }

    private static Browser.NewContextOptions contextOptions(BrowserConfiguration configuration, WebSettings settings,
                                                            String storageState) {
        Browser.NewContextOptions options = contextOptions(configuration, settings);
        if (storageState != null) {
            options.setStorageState(storageState);
        }
        return options;
    }

    static Browser.NewContextOptions contextOptions(BrowserConfiguration configuration, WebSettings settings) {
        var context = settings.getContextSettings();
        var options = new Browser.NewContextOptions()
                .setLocale("en-US")
                .setTimezoneId(ZoneId.systemDefault().getId());
        if (context != null && context.isShouldSetContextSettings()) {
            options.setIgnoreHTTPSErrors(context.isIgnoreHTTPSErrors())
                    .setAcceptDownloads(context.isAcceptDownloads())
                    .setBypassCSP(context.isBypassCSP())
                    .setJavaScriptEnabled(context.isJsEnabled());
            if (context.isShouldSetGeolocation() && context.getGeolocation() != null) {
                options.setGeolocation(context.getGeolocation());
            }
            if (context.getHTTPCredentials() != null) {
                options.setHttpCredentials(context.getHTTPCredentials());
            }
            if (context.getLocale() != null && !context.getLocale().isBlank()) {
                options.setLocale(context.getLocale());
            }
            if (context.getTimezoneId() != null && !context.getTimezoneId().isBlank()) {
                options.setTimezoneId(context.getTimezoneId());
            }
            if (context.getUserAgent() != null && !context.getUserAgent().isBlank()) {
                options.setUserAgent(context.getUserAgent());
            }
            if (context.getPermissions() != null) {
                options.setPermissions(context.getPermissions());
            }
        }
        if (settings.getBaseUrl() != null && !settings.getBaseUrl().isBlank()) {
            options.setBaseURL(settings.getBaseUrl());
        }

        var device = configuration.getDeviceName();
        if (configuration.getBrowserTypes() == BrowserTypes.CHROME_MOBILE && device == DeviceName.NOT_SET) {
            throw new IllegalArgumentException("Mobile Chromium requires a device profile");
        }
        if (device != DeviceName.NOT_SET) {
            options.setViewportSize(device.getWidth(), device.getHeight())
                    .setDeviceScaleFactor(device.getScaleFactor())
                    .setIsMobile(true)
                    .setHasTouch(true)
                    .setUserAgent(DeviceName.CHROME_MOBILE_USER_AGENT);
        } else {
            int width = configuration.getWidth() > 0 ? configuration.getWidth() : settings.getDefaultBrowserWidth();
            int height = configuration.getHeight() > 0 ? configuration.getHeight() : settings.getDefaultBrowserHeight();
            if (width > 0 && height > 0) {
                options.setViewportSize(width, height);
            }
        }
        return options;
    }

    static void applyTimeouts(BrowserContext context, TimeoutSettings timeoutSettings) {
        if (timeoutSettings == null) {
            return;
        }
        var timeouts = timeoutSettings.inMilliseconds();
        if (timeouts.getElementWaitTimeout() > 0) {
            context.setDefaultTimeout(timeouts.getElementWaitTimeout());
        }
        if (timeouts.getPageLoadTimeout() > 0) {
            context.setDefaultNavigationTimeout(timeouts.getPageLoadTimeout());
        }
    }

    private record BrowserSession(WrappedBrowser browser, BrowserConfiguration configuration, LaunchOptions launchOptions,
                                  boolean retainContext) {
    }

    private record LaunchOptions(BrowserTypes type, boolean headless) {
    }
}
