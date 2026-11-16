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

package solutions.bellatrix.playwright.services;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.assertions.PageAssertions;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.junit.jupiter.api.Assertions;
import solutions.bellatrix.core.utilities.Log;
import solutions.bellatrix.core.utilities.SecretMasker;
import solutions.bellatrix.core.utilities.SingletonFactory;
import solutions.bellatrix.core.utilities.Wait;
import solutions.bellatrix.playwright.components.Frame;
import solutions.bellatrix.playwright.components.common.validate.Validator;
import solutions.bellatrix.playwright.infrastructure.WrappedBrowser;
import solutions.bellatrix.playwright.infrastructure.httptraffic.HttpMethod;
import solutions.bellatrix.playwright.utilities.DebugLogger;
import solutions.bellatrix.playwright.utilities.Settings;
import solutions.bellatrix.playwright.utilities.functionalinterfaces.Assertable;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class BrowserService extends WebService {
    private static final Pattern CONSOLE_POSITION = Pattern.compile(":\\d+:\\d+$");

    public BrowserContext context() {
        return Objects.requireNonNull(wrappedBrowser().getCurrentContext(), "No browser context is open");
    }

    public String version() {
        return wrappedBrowser().getBrowser().version();
    }

    public List<String> consoleErrors() {
        return wrappedBrowser().getConsoleErrors();
    }

    public List<WrappedBrowser.ConsoleEntry> consoleEntries() {
        return wrappedBrowser().getConsoleEntries();
    }

    public void discardConsoleErrors(Predicate<WrappedBrowser.ConsoleEntry> expectedError) {
        wrappedBrowser().discardConsoleErrors(expectedError);
    }

    public List<String> getSevereLogEntries() {
        List<String> whitelist = consoleErrorsWhitelist();
        List<String> messages = consoleErrors();
        List<WrappedBrowser.ConsoleEntry> errors = consoleErrorEntries();
        List<String> severe = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            String logLine = index < errors.size() ? consoleLogLine(errors.get(index)) : messages.get(index);
            if (whitelist.stream().noneMatch(logLine::contains)) {
                severe.add(messages.get(index));
            }
        }
        return List.copyOf(severe);
    }

    public void assertNoConsoleErrorsLogged() {
        var errors = getSevereLogEntries();
        org.junit.jupiter.api.Assertions.assertEquals(List.of(), errors, () -> {
            List<String> whitelist = consoleErrorsWhitelist();
            List<String> locations = consoleErrorEntries().stream()
                    .filter(entry -> whitelist.stream().noneMatch(consoleLogLine(entry)::contains))
                    .map(entry -> consoleResourceLocation(withoutConsolePosition(entry.location())))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            String message = "Severe errors found in browser console";
            return locations.isEmpty() ? message : message + " (resource locations: " + locations + ")";
        });
    }

    private List<WrappedBrowser.ConsoleEntry> consoleErrorEntries() {
        return consoleEntries().stream().filter(entry -> "error".equals(entry.type())).toList();
    }

    private static List<String> consoleErrorsWhitelist() {
        return Objects.requireNonNullElse(Settings.web().getConsoleErrorsWhitelist(), List.of());
    }

    private static String consoleLogLine(WrappedBrowser.ConsoleEntry entry) {
        String location = withoutConsolePosition(entry.location());
        return location == null || location.isEmpty() ? entry.message() : location + " - " + entry.message();
    }

    private static String withoutConsolePosition(String location) {
        return location == null ? null : CONSOLE_POSITION.matcher(location).replaceFirst("");
    }

    private static String consoleResourceLocation(String location) {
        if (location == null) {
            return null;
        }
        try {
            var uri = URI.create(location);
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                return null;
            }
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return SecretMasker.mask(uri.getScheme() + "://" + uri.getHost() + port + uri.getRawPath());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public void assertConsoleErrorLogged(String message, Level level) {
        var type = switch (level.getName()) {
            case "SEVERE" -> "error";
            case "WARNING" -> "warning";
            default -> throw new IllegalArgumentException("Unsupported console severity: " + level);
        };
        var entries = consoleEntries();
        org.junit.jupiter.api.Assertions.assertTrue(entries.stream().anyMatch(
                        entry -> type.equals(entry.type()) && entry.message().contains(message)),
                () -> "Expected console " + type + " containing " + message + " but found " + entries);
    }

    public void copyTextToClipboard(String text) {
        var page = clipboardPage();
        page.evaluate("text => navigator.clipboard.writeText(text)", Objects.requireNonNull(text));
    }

    public String getLastClipboardEntry() {
        return (String) clipboardPage().evaluate("() => navigator.clipboard.readText()");
    }

    public void runAndWaitForClipboardCopy(Runnable action) {
        var page = clipboardPage();
        page.evaluate("() => navigator.clipboard.writeText('')");
        action.run();
        waitUntil(() -> !((String) page.evaluate("() => navigator.clipboard.readText()")).isEmpty());
    }

    private Page clipboardPage() {
        var page = page();
        if (!Boolean.TRUE.equals(page.evaluate("() => Boolean(navigator.clipboard)"))) {
            throw new IllegalStateException("Browser clipboard requires a secure page");
        }
        var permissions = List.of("clipboard-read", "clipboard-write");
        if ("file".equalsIgnoreCase(URI.create(page.url()).getScheme())) {
            context().grantPermissions(permissions);
        } else {
            var origin = (String) page.evaluate("location.origin");
            context().grantPermissions(permissions, new BrowserContext.GrantPermissionsOptions().setOrigin(origin));
        }
        page.bringToFront();
        return page;
    }

    public String getUrl() {
        return page().url();
    }

    public String getTitle() {
        return page().title();
    }

    public void back() {
        page().goBack();
    }

    public void forward() {
        page().goForward();
    }

    public void refresh() {
        page().reload();
    }

    public String getPageSource() {
        return page().content();
    }

    public void scrollToBottom() {
        wrappedBrowser().getCurrentPage().evaluate("window.scrollTo(0, document.body.scrollHeight)");
    }

    public void scrollToTop() {
        wrappedBrowser().getCurrentPage().evaluate("window.scrollTo(0, 0)");
    }

    /**
     * Performs action and waits for a new Page to be created and registered in the context. <br>
     * @param action Callback that performs the action triggering the event.
     */
    public Page runAndWaitForPage(Runnable action) {
        return context().waitForPage(action);
    }

    /**
     * Performs action and waits for a new Page to be created and registered in the context. <br>
     * If predicate is provided, it passes Page value into the predicate function and waits for predicate(event)
     * to return a truthy value.
     * @param action Callback that performs the action triggering the event.
     */
    public Page runAndWaitForPage(Runnable action, BrowserContext.WaitForPageOptions options) {
        return context().waitForPage(options, action);
    }

    public Page runAndWaitForPopup(Runnable action) {
        return page().waitForPopup(action);
    }

    public void waitForCondition(BooleanSupplier condition) {
        wrappedBrowser().getCurrentContext().waitForCondition(condition);
    }

    public void waitForCondition(BooleanSupplier condition, BrowserContext.WaitForConditionOptions options) {
        wrappedBrowser().getCurrentContext().waitForCondition(condition, options);
    }

    public void waitUntil(BooleanSupplier condition) {
        var options = new BrowserContext.WaitForConditionOptions();
        var timeout = Settings.timeout().inMilliseconds().getWaitUntilReadyTimeout();
        if (timeout > 0) {
            options.setTimeout(timeout);
        }
        waitForCondition(condition, options);
    }

    public boolean tryWaitUntil(BooleanSupplier condition) {
        try {
            waitUntil(condition);
            return true;
        } catch (TimeoutError timeout) {
            return false;
        }
    }

    public ConsoleMessage runAndWaitForConsoleMessage(Runnable action) {
        return wrappedBrowser().getCurrentContext().waitForConsoleMessage(action);
    }

    public ConsoleMessage runAndWaitForConsoleMessage(Runnable action, BrowserContext.WaitForConsoleMessageOptions options) {
        return wrappedBrowser().getCurrentContext().waitForConsoleMessage(options, action);
    }

    /**
     * In case an action on the page is expected to open a new tab in the browser, before calling this method
     * one should pass the action to {@link #runAndWaitForPage(Runnable, BrowserContext.WaitForPageOptions)}.
     */
    public void switchToTab(Page page) {
        wrappedBrowser().setCurrentPage(page);
        page.bringToFront();
    }

    /**
     * In case an action on the page is expected to open a new tab in the browser, before calling this method
     * one should pass the action to {@link #runAndWaitForPage(Runnable, BrowserContext.WaitForPageOptions)}.
     */
    public void switchToFirstTab() {
        var pages = wrappedBrowser().getCurrentContext().pages();
        var firstTab = pages.get(0);
        firstTab.bringToFront();
        wrappedBrowser().setCurrentPage(firstTab);
    }

    /**
     * In case an action on the page is expected to open a new tab in the browser, before calling this method
     * one should pass the action to {@link #runAndWaitForPage(Runnable, BrowserContext.WaitForPageOptions)}.
     */
    public void switchToLastTab() {
        var pages = wrappedBrowser().getCurrentContext().pages();
        var lastTab = pages.get(pages.size() - 1);
        lastTab.bringToFront();
        wrappedBrowser().setCurrentPage(lastTab);
    }

    /**
     * There is no need for such method because WebElement works with iframes by default.
     * <br>
     * You can also find iframes with all of the ComponentCreateService's methods.
     */
    @Deprecated
    public Frame getFrame(String name) {
        return SingletonFactory.getInstance(ComponentCreateService.class).byName(Frame.class, name);
    }

    /**
     * There is no need for such method because WebElement works with iframes by default.
     * <br>
     * You can also find iframes with all of the ComponentCreateService's methods.
     */
    @Deprecated
    public Frame getFrameByUrl(String url) {
        return SingletonFactory.getInstance(ComponentCreateService.class).byCss(Frame.class, String.format("[href='%s']", url));
    }

    /**
     * There is no need for such method because WebElement works with iframes by default.
     * <br>
     * You can also find iframes with all of the ComponentCreateService's methods.
     */
    @Deprecated
    public Frame getFrameByPartialUrl(String partialUrl) {
        return SingletonFactory.getInstance(ComponentCreateService.class).byAttributeContaining(Frame.class, "href", partialUrl);
    }

    public void clearSessionStorage() {
        page().evaluate("sessionStorage.clear();");
    }

    public void removeItemFromLocalStorage(String item) {
        page().evaluate("key => localStorage.removeItem(key)", item);
    }

    public boolean isItemPresentInLocalStorage(String item) {
        return getItemFromLocalStorage(item) != null;
    }

    public String getItemFromLocalStorage(String key) {
        return (String) page().evaluate("key => localStorage.getItem(key)", key);
    }

    public void setItemInLocalStorage(String item, String value) {
        page().evaluate("([key, value]) => localStorage.setItem(key, value)", new String[]{item, value});
    }

    public void clearLocalStorage() {
        page().evaluate("localStorage.clear();");
    }

    public void injectInfoNotificationToast(String message) {
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.INFORMATION);
    }

    public void injectInfoNotificationToast(String format, Object... args) {
        String message = String.format(format, args);
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.INFORMATION);
    }

    public void injectSuccessNotificationToast(String message) {
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.SUCCESS);
    }

    public void injectSuccessNotificationToast(String format, Object... args) {
        String message = String.format(format, args);
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.SUCCESS);
    }

    public void injectErrorNotificationToast(String message) {
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.ERROR);
    }

    public void injectErrorNotificationToast(String format, Object... args) {
        String message = String.format(format, args);
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.ERROR);
    }

    public void injectWarningNotificationToast(String message) {
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.WARNING);
    }


    public void injectWarningNotificationToast(String format, Object... args) {
        String message = String.format(format, args);
        var timeout = Settings.web().getNotificationToastTimeout();
        injectNotificationToast(message, timeout, ToastNotificationType.WARNING);
    }

    public void injectNotificationToast(String message, long timeoutMillis, ToastNotificationType type) {
        String escapedMessage = StringEscapeUtils.escapeEcmaScript(message);
        String executionScript = """
                window.$bellatrixToastContainer = !window.$bellatrixToastContainer ? Object.assign(document.createElement('div'), {id: 'bellatrixToastContainer', style: 'position: fixed; top: 0; height: 100vh; padding-bottom: 20px; display: flex; pointer-events: none; z-index: 2147483646; justify-content: flex-end; flex-direction: column; overflow: hidden;'}) : window.$bellatrixToastContainer;
                let $message = '""" + escapedMessage + """
                ';
                let $timeout = """ + timeoutMillis + """
                ;
                let $type = '""" + type.toString() + """
                ';
                if (!document.querySelector('#bellatrixToastContainer')) document.body.appendChild(window.$bellatrixToastContainer);
                let $bellatrixToast
                switch ($type.toLowerCase()) {
                    case 'warning':
                        $bellatrixToast = Object.assign(document.createElement('div'), {textContent: $message.trim() ? $message : 'message not set', style: 'pointer-events: none; z-index: 2147483647; color: ' + ($message.trim() ? '#2e0f00' : '#2e0f0088') + '; width: fit-content; background-color: #fdefc9; margin: 5px 10px; border-radius: 10px; padding: 15px 10px 15px 52px; background-repeat: no-repeat; background-position: 5px center; font-family: Arial, Helvetica, sans-serif; font-size: 15px; line-height: 15px; box-shadow: 0px 0.6px 0.7px rgba(0, 0, 0, 0.1), 0px 1.3px 1.7px rgba(0, 0, 0, 0.116), 0px 2.3px 3.5px rgba(0, 0, 0, 0.128), 0px 4.2px 7.3px rgba(0, 0, 0, 0.135), 0px 10px 20px rgba(0, 0, 0, 0.13); background-image: url(\\'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 22 22"><circle cx="11" cy="-1041.36" r="8" transform="matrix(1 0 0-1 0-1030.36)" opacity=".98" fill="%23ffb816"/><path d="m-26.309 18.07c-1.18 0-2.135.968-2.135 2.129v12.82c0 1.176.948 2.129 2.135 2.129 1.183 0 2.135-.968 2.135-2.129v-12.82c0-1.176-.946-2.129-2.135-2.129zm0 21.348c-1.18 0-2.135.954-2.135 2.135 0 1.18.954 2.135 2.135 2.135 1.181 0 2.135-.954 2.135-2.135 0-1.18-.952-2.135-2.135-2.135z" transform="matrix(.30056 0 0 .30056 18.902 1.728)" fill="%23fff" stroke="%23fff"/></svg>\\');'});
                        break;
                    case 'error':
                        $bellatrixToast = Object.assign(document.createElement('div'), {textContent: $message.trim() ? $message : 'message not set', style: 'pointer-events: none; z-index: 2147483647; color: ' + ($message.trim() ? '#2e0004' : '#2e000488') + '; width: fit-content; background-color: #fdc9d2; margin: 5px 10px; border-radius: 10px; padding: 15px 10px 15px 52px; background-repeat: no-repeat; background-position: 5px center; font-family: Arial, Helvetica, sans-serif; font-size: 15px; line-height: 15px; box-shadow: 0px 0.6px 0.7px rgba(0, 0, 0, 0.1), 0px 1.3px 1.7px rgba(0, 0, 0, 0.116), 0px 2.3px 3.5px rgba(0, 0, 0, 0.128), 0px 4.2px 7.3px rgba(0, 0, 0, 0.135), 0px 10px 20px rgba(0, 0, 0, 0.13); background-image: url(\\'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 22 22"><defs><linearGradient gradientUnits="userSpaceOnUse" y2="-2.623" x2="0" y1="986.67"><stop stop-color="%23ffce3b"/><stop offset="1" stop-color="%23ffd762"/></linearGradient><linearGradient id="0" gradientUnits="userSpaceOnUse" y1="986.67" x2="0" y2="-2.623"><stop stop-color="%23ffce3b"/><stop offset="1" stop-color="%23fef4ab"/></linearGradient><linearGradient gradientUnits="userSpaceOnUse" x2="1" x1="0" xlink:href="%230"/></defs><g transform="matrix(2 0 0 2-11-2071.72)"><path transform="translate(7 1037.36)" d="m4 0c-2.216 0-4 1.784-4 4 0 2.216 1.784 4 4 4 2.216 0 4-1.784 4-4 0-2.216-1.784-4-4-4" fill="%23da4453"/><path d="m11.906 1041.46l.99-.99c.063-.062.094-.139.094-.229 0-.09-.031-.166-.094-.229l-.458-.458c-.063-.062-.139-.094-.229-.094-.09 0-.166.031-.229.094l-.99.99-.99-.99c-.063-.062-.139-.094-.229-.094-.09 0-.166.031-.229.094l-.458.458c-.063.063-.094.139-.094.229 0 .09.031.166.094.229l.99.99-.99.99c-.063.062-.094.139-.094.229 0 .09.031.166.094.229l.458.458c.063.063.139.094.229.094.09 0 .166-.031.229-.094l.99-.99.99.99c.063.063.139.094.229.094.09 0 .166-.031.229-.094l.458-.458c.063-.062.094-.139.094-.229 0-.09-.031-.166-.094-.229l-.99-.99" fill="%23fff"/></g></svg>\\');'});
                        break;
                    case 'success':
                        $bellatrixToast = Object.assign(document.createElement('div'), {textContent: $message.trim() ? $message : 'message not set', style: 'pointer-events: none; z-index: 2147483647; color: ' + ($message.trim() ? '#002e0a' : '#002e0a88') + '; width: fit-content; background-color: #c9fdd4; margin: 5px 10px; border-radius: 10px; padding: 15px 10px 15px 52px; background-repeat: no-repeat; background-position: 5px center; font-family: Arial, Helvetica, sans-serif; font-size: 15px; line-height: 15px; box-shadow: 0px 0.6px 0.7px rgba(0, 0, 0, 0.1), 0px 1.3px 1.7px rgba(0, 0, 0, 0.116), 0px 2.3px 3.5px rgba(0, 0, 0, 0.128), 0px 4.2px 7.3px rgba(0, 0, 0, 0.135), 0px 10px 20px rgba(0, 0, 0, 0.13); background-image: url(\\'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 22 22"><g transform="matrix(1.99997 0 0 1.99997-10.994-2071.68)" fill="%23da4453"><rect y="1037.36" x="7" height="8" width="8" fill="%2332c671" rx="4"/><path d="m123.86 12.966l-11.08-11.08c-1.52-1.521-3.368-2.281-5.54-2.281-2.173 0-4.02.76-5.541 2.281l-53.45 53.53-23.953-24.04c-1.521-1.521-3.368-2.281-5.54-2.281-2.173 0-4.02.76-5.541 2.281l-11.08 11.08c-1.521 1.521-2.281 3.368-2.281 5.541 0 2.172.76 4.02 2.281 5.54l29.493 29.493 11.08 11.08c1.52 1.521 3.367 2.281 5.54 2.281 2.172 0 4.02-.761 5.54-2.281l11.08-11.08 58.986-58.986c1.52-1.521 2.281-3.368 2.281-5.541.0001-2.172-.761-4.02-2.281-5.54" fill="%23fff" transform="matrix(.0436 0 0 .0436 8.177 1039.72)" stroke="none" stroke-width="9.512"/></g></svg>\\');'});
                        break;
                    case 'information':
                        $bellatrixToast = Object.assign(document.createElement('div'), {textContent: $message.trim() ? $message : 'message not set', style: 'pointer-events: none; z-index: 2147483647; color: ' + ($message.trim() ? '#00122e' : '#00122e88') + '; width: fit-content; background-color: #c9ecfd; margin: 5px 10px; border-radius: 10px; padding: 15px 10px 15px 52px; background-repeat: no-repeat; background-position: 7.5px center; font-family: Arial, Helvetica, sans-serif; font-size: 15px; line-height: 15px; background-size: 40px; box-shadow: 0px 0.6px 0.7px rgba(0, 0, 0, 0.1), 0px 1.3px 1.7px rgba(0, 0, 0, 0.116), 0px 2.3px 3.5px rgba(0, 0, 0, 0.128), 0px 4.2px 7.3px rgba(0, 0, 0, 0.135), 0px 10px 20px rgba(0, 0, 0, 0.13); background-image: url(\\'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><g transform="matrix(.92857 0 0 .92857-691.94-149.37)"><circle cx="779.63" cy="195.32" r="28" fill="%230c9fdd"/><g fill="%23fff" fill-rule="evenodd" fill-opacity=".855"><path d="m779.62639 179.16433a3.589743 3.589743 0 0 1 3.58975 3.58975 3.589743 3.589743 0 0 1 -3.58975 3.58974 3.589743 3.589743 0 0 1 -3.58974 -3.58974 3.589743 3.589743 0 0 1 3.58974 -3.58975"/><path d="m779.24 189.93h.764c1.278 0 2.314 1.028 2.314 2.307v16.925c0 1.278-1.035 2.307-2.314 2.307h-.764c-1.278 0-2.307-1.028-2.307-2.307v-16.925c0-1.278 1.028-2.307 2.307-2.307"/></g></g></svg>\\');'});
                }
                window.$bellatrixToastContainer.appendChild($bellatrixToast);
                setTimeout($bellatrixToast.remove.bind($bellatrixToast), $timeout);""";
        page().evaluate(executionScript);
    }

    public void waitForReactPageLoadsCompletely() {
        long waitUntilReadyTimeout = Settings.timeout().inMilliseconds().getWaitUntilReadyTimeout();
        long pollingInterval = Validator.CONDITION_POLLING_INTERVAL_MILLISECONDS;

        Wait.Comparator dataReactRootNotNull = () -> {
            return (boolean)page().evaluate("document.querySelector('data-reactroot') !== null");
        };

        Wait.Comparator loadEventEndBiggerThanZero = () -> {
            return (boolean)page().evaluate("window.performance.timing.loadEventEnd > 0");
        };

        Validator.validateCondition(dataReactRootNotNull, waitUntilReadyTimeout, pollingInterval);
        Validator.validateCondition(loadEventEndBiggerThanZero, waitUntilReadyTimeout, pollingInterval);
    }

    public void waitForJavaScriptAnimations() {
        long waitForJavaScriptAnimationsTimeout = Settings.timeout().inMilliseconds().getWaitForJavaScriptAnimationsTimeout();
        long pollingInterval = Validator.CONDITION_POLLING_INTERVAL_MILLISECONDS;

        Wait.Comparator javaScriptAnimationsWait = () -> {
            return (boolean)page().evaluate("jQuery && jQuery(':animated').length === 0");
        };

        Validator.validateCondition(javaScriptAnimationsWait, waitForJavaScriptAnimationsTimeout, pollingInterval);
    }

    public void waitForPartialUrl(String partialUrl) {
        var options = new Page.WaitForURLOptions().setWaitUntil(WaitUntilState.COMMIT);
        var timeout = Settings.timeout().inMilliseconds().getWaitForPartialUrl();
        if (timeout > 0) {
            options.setTimeout(timeout);
        }
        page().waitForURL(url -> url.contains(partialUrl), options);
    }

    public void waitForAngular() {
        long angularTimeout = Settings.timeout().inMilliseconds().getWaitForAngularTimeout();
        long pollingInterval = Validator.CONDITION_POLLING_INTERVAL_MILLISECONDS;

        String isAngular5 = (String)page().evaluate("getAllAngularRootElements()[0].attributes['ng-version']");

        if (StringUtils.isBlank(isAngular5)) {
            Validator.validateCondition(() -> {
                return (boolean)page().evaluate("window.getAllAngularTestabilities().findIndex(x=>!x.isStable()) === -1");
            }, angularTimeout, pollingInterval);
        } else {
            boolean isAngularDefined = (boolean)page().evaluate("window.angular === undefined");
            if (!isAngularDefined) {
                boolean isAngularInjectorUndefined = (boolean)page().evaluate("angular.element(document).injector() === undefined");
                if (!isAngularInjectorUndefined) {
                    Validator.validateCondition(() -> {
                        return (boolean)page().evaluate("angular.element(document).injector().get('$http').pendingRequests.length === 0");
                    }, angularTimeout, pollingInterval);
                }
            }
        }
    }

    /**
     * This method is recommended for waiting for certain page state as it is native to playwright and thus - faster.
     */
    public void waitUntil(LoadState state) {
        page().waitForLoadState(state, new Page.WaitForLoadStateOptions().setTimeout(Settings.timeout().inMilliseconds().getPageLoadTimeout()));
    }

    public void waitForAjax() {
        waitForAjaxToComplete();
    }

    public boolean waitForAjaxToComplete() {
        var browser = wrappedBrowser();
        var page = page();
        requireOpenAjaxPage(browser, page);
        double documentStartedAt = browser.documentStartedAt(page);
        long latestAjaxAtStart = browser.latestAjaxSequence();
        var options = new BrowserContext.WaitForConditionOptions();
        var timeout = Settings.timeout().inMilliseconds().getWaitForAjaxTimeout();
        if (timeout > 0) {
            options.setTimeout(timeout);
        }
        try {
            context().waitForCondition(() -> {
                requireOpenAjaxPage(browser, page);
                return !browser.hasPendingAjax(page, documentStartedAt);
            }, options);
            return true;
        } catch (TimeoutError observationTimeout) {
            requireOpenAjaxPage(browser, page);
            Log.info("Timed out waiting for pending AJAX requests to complete. " + browser.pendingAjaxSummary(page));
            // A request that was pending for the whole wait is hung: later waits must not pay the timeout for it again.
            browser.stopAwaitingAjax(page, latestAjaxAtStart);
            return false;
        }
    }

    private static void requireOpenAjaxPage(WrappedBrowser browser, Page page) {
        if (!browser.getBrowser().isConnected() || page.isClosed()) {
            throw new PlaywrightException("Cannot observe AJAX after the browser page closed");
        }
    }

    public void waitForRequest(String partialUrl) {
        try {
            waitForResource(partialUrl, null, Duration.ofSeconds(Settings.timeout().getWaitUntilReadyTimeout()));
        } catch (TimeoutError timeout) {
            throw new TimeoutError("No completed request matched " + partialUrl, timeout);
        }
    }

    public void tryWaitForResponse(String partialUrl) {
        tryWaitForResponse(partialUrl, 0);
    }

    public void tryWaitForResponse(String partialUrl, int additionalTimeoutInSeconds) {
        try {
            if (NetworkService.isCapturing()) {
                NetworkService.waitForResponse(partialUrl, HttpMethod.GET, additionalTimeoutInSeconds);
            } else {
                var timeout = Duration.ofSeconds(Settings.timeout().getWaitForAjaxTimeout() + additionalTimeoutInSeconds);
                waitForResource(partialUrl, null, timeout);
            }
        } catch (TimeoutError timeout) {
            Log.info("Timed out waiting for the optional response matching %s", partialUrl);
        }
    }

    public void waitForAjaxRequest(String requestPartialUrl, int additionalTimeoutInSeconds) {
        var timeout = Duration.ofSeconds(Settings.timeout().getWaitForAjaxTimeout() + additionalTimeoutInSeconds);
        waitForResource(requestPartialUrl, "xhr", timeout);
    }

    public void waitForAjaxRequest(String requestPartialUrl) {
        waitForAjaxRequest(requestPartialUrl, 0);
    }

    private void waitForResource(String partialUrl, String resourceType, Duration timeout) {
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Request timeout must be nonnegative");
        }
        wrappedBrowser().waitForResource(page(), partialUrl, resourceType, timeout);
    }

    public void waitUntilPageLoadsCompletely() {
        waitUntil(LoadState.LOAD);
    }

    public void validateLandedOnPage(String partialUrl) {
        validateLandedOnPage(partialUrl, false);
    }

    public void validateLandedOnPage(String partialUrl, boolean shouldUrlEncode) {
        if (shouldUrlEncode) {
            partialUrl = URLEncoder.encode(partialUrl, StandardCharsets.UTF_8);
        }

        Pattern pattern = Pattern.compile(".*" + Pattern.quote(partialUrl) + ".*");
        var timeout = Settings.timeout().inMilliseconds().getWaitForPartialUrl();


        Assertable assertion = () -> PlaywrightAssertions.assertThat(page()).hasURL(pattern, new PageAssertions.HasURLOptions().setTimeout(timeout));
        String log = String.format("The expected partialUrl: %s was not found in the page's url: %s", partialUrl, getUrl());

        DebugLogger.assertAndLogOnFail(assertion, log);
    }

    public void validateNotLandedOnPage(String partialUrl) {
        validateNotLandedOnPage(partialUrl, false);
    }

    public void validateNotLandedOnPage(String partialUrl, boolean shouldUrlEncode) {
        if (shouldUrlEncode) {
            partialUrl = URLEncoder.encode(partialUrl, StandardCharsets.UTF_8);
        }

        Pattern pattern = Pattern.compile(".*" + Pattern.quote(partialUrl) + ".*");
        var timeout = Settings.timeout().inMilliseconds().getWaitForPartialUrl();

        Assertable assertion = () -> PlaywrightAssertions.assertThat(page()).not().hasURL(pattern, new PageAssertions.HasURLOptions().setTimeout(timeout));
        String log = String.format("The expected partialUrl: %s was found in the page's url: %s", partialUrl, getUrl());

        DebugLogger.assertAndLogOnFail(assertion, log);
    }

    public void validateUrl(String fullUrl) {
        var timeout = Settings.timeout().inMilliseconds().getValidationsTimeout();
        Assertable assertion = () -> PlaywrightAssertions.assertThat(page()).hasURL(fullUrl, new PageAssertions.HasURLOptions().setTimeout(timeout));
        String log = String.format("Expected URL:\n%s\nIs different than the actual one:\n%s", fullUrl, getUrl());

        DebugLogger.assertAndLogOnFail(assertion, log);
    }

    public void assertUrlPath(String urlPath) {
        String currentBrowserUrl = getUrl();
        URI actualUri = null;
        try {
            actualUri = new URI(currentBrowserUrl);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        Assertions.assertEquals(urlPath, actualUri.getPath(),
                "Expected URL path is different than the Actual one.");
    }

    public void assertUrlPathAndQuery(String pathAndQuery) {
        String currentBrowserUrl = getUrl();
        URI actualUri = null;
        try {
            actualUri = new URI(currentBrowserUrl);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        Assertions.assertEquals(pathAndQuery, actualUri.getPath() + "?" + actualUri.getQuery(),
                "Expected URL is different than the Actual one.");
    }

    public Page page() {
        return Objects.requireNonNull(wrappedBrowser().getCurrentPage(), "No browser page is open");
    }
}
