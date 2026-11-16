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
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.Assertions;
import solutions.bellatrix.core.utilities.Log;
import solutions.bellatrix.playwright.infrastructure.PlaywrightService;
import solutions.bellatrix.playwright.infrastructure.httptraffic.CaptureJson;
import solutions.bellatrix.playwright.infrastructure.httptraffic.CapturedRequest;
import solutions.bellatrix.playwright.infrastructure.httptraffic.HttpMethod;
import solutions.bellatrix.playwright.infrastructure.httptraffic.RequestMatcher;
import solutions.bellatrix.playwright.infrastructure.httptraffic.Traffic;
import solutions.bellatrix.playwright.utilities.Settings;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class NetworkService extends WebService {
    public static void install(BrowserContext context) {
        Traffic.install(context);
    }

    public static void uninstall(BrowserContext context) {
        Traffic.uninstall(context);
    }

    public static boolean isCapturing() {
        return Traffic.find(currentContext()) != null;
    }

    public static CaptureCheckpoint checkpoint() {
        return new CaptureCheckpoint(capture().checkpoint());
    }

    public static void newHar() {
        clearHistory();
    }

    public static void clearHistory() {
        capture().reset();
    }

    public static List<CapturedRequest> getCapturedEntries() {
        return getCapturedEntries(null);
    }

    public static List<CapturedRequest> getCapturedEntries(CaptureCheckpoint checkpoint) {
        return capture().entries(boundary(checkpoint));
    }

    public static CapturedRequest waitForRequest(String url, HttpMethod method, int additionalSeconds) {
        return waitForRequest(url, method, additionalSeconds, null);
    }

    public static CapturedRequest waitForRequest(String url, HttpMethod method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint) {
        return waitForRequest(url, method.name(), additionalSeconds, checkpoint);
    }

    private static CapturedRequest waitForRequest(String url, String method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint) {
        var capture = capture();
        var matcher = RequestMatcher.forUrl(url, method);
        try {
            return capture.waitUntil(() -> capture.find(boundary(checkpoint), matcher), timeout(additionalSeconds));
        } catch (TimeoutError ignored) {
            Log.error("Expected %s request was not observed for %s", method, url);
            return null;
        }
    }

    public static List<String> waitForRequests(List<String> urls, HttpMethod method, int additionalSeconds,
                                               CaptureCheckpoint checkpoint) {
        return waitForRequests(urls, method.name(), additionalSeconds, checkpoint);
    }

    private static List<String> waitForRequests(List<String> urls, String method, int additionalSeconds,
                                               CaptureCheckpoint checkpoint) {
        if (urls.isEmpty()) {
            return List.of();
        }
        var capture = capture();
        var missing = new LinkedHashMap<String, Predicate<CapturedRequest>>();
        for (String url : urls) {
            missing.put(url, RequestMatcher.forUrl(url, method));
        }
        try {
            capture.waitUntil(() -> {
                var entries = capture.entries(boundary(checkpoint));
                missing.values().removeIf(matcher -> entries.stream().anyMatch(matcher));
                return missing.isEmpty() ? Boolean.TRUE : null;
            }, timeout(additionalSeconds));
        } catch (TimeoutError ignored) {
            Log.error("Requests not observed before the shared timeout: %s", String.join(", ", missing.keySet()));
        }
        return List.copyOf(missing.keySet());
    }

    public static CapturedRequest waitForResponse(String url, HttpMethod method, int additionalSeconds) {
        return waitForResponse(url, method, additionalSeconds, null);
    }

    public static CapturedRequest waitForResponse(String url, HttpMethod method, int additionalSeconds,
                                                   CaptureCheckpoint checkpoint) {
        return waitForResponse(url, method.name(), additionalSeconds, checkpoint);
    }

    private static CapturedRequest waitForResponse(String url, String method, int additionalSeconds,
                                                   CaptureCheckpoint checkpoint) {
        var matcher = RequestMatcher.forUrl(url, method);
        return awaitResponse(capture(), matcher, timeout(additionalSeconds), checkpoint, null,
                "%s response for %s".formatted(method, url), !"DELETE".equalsIgnoreCase(method));
    }

    public static CapturedRequest waitForResponse(Predicate<CapturedRequest> matcher, Duration timeout,
                                                   CaptureCheckpoint checkpoint) {
        var capture = capture();
        return capture.waitUntil(() -> capture.find(boundary(checkpoint),
                entry -> entry.completed() && matcher.test(entry)), timeout);
    }

    public static CapturedRequest waitForResponseAtEndpoint(String endpoint, HttpMethod method, int additionalSeconds,
                                                            CaptureCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "An action checkpoint is required");
        return awaitResponse(capture(), RequestMatcher.forEndpoint(endpoint, method.name()),
                timeout(additionalSeconds), checkpoint, null,
                "%s response for %s".formatted(method, endpoint), method != HttpMethod.DELETE);
    }

    public static <T> T waitForResponseAtEndpoint(String endpoint, HttpMethod method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint, Class<T> responseType) {
        return waitForResponseAtEndpoint(endpoint, method.name(), additionalSeconds, checkpoint, responseType);
    }

    private static <T> T waitForResponseAtEndpoint(String endpoint, String method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint, Class<T> responseType) {
        return waitForResponseAtEndpoint(endpoint, method, additionalSeconds, checkpoint, (Type) responseType);
    }

    public static <T> T waitForResponseAtEndpoint(String endpoint, HttpMethod method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint, Type responseType) {
        return waitForResponseAtEndpoint(endpoint, method.name(), additionalSeconds, checkpoint, responseType);
    }

    private static <T> T waitForResponseAtEndpoint(String endpoint, String method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint, Type responseType) {
        Objects.requireNonNull(checkpoint, "An action checkpoint is required");
        return readEndpoint(endpoint, method, timeout(additionalSeconds), checkpoint, responseType, null);
    }

    public static <T> T waitForResponseAtEndpoint(String endpoint, HttpMethod method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint, Class<T> responseType,
                                                  Runnable action) {
        return waitForResponseAtEndpoint(endpoint, method.name(), additionalSeconds, checkpoint, responseType, action);
    }

    private static <T> T waitForResponseAtEndpoint(String endpoint, String method, int additionalSeconds,
                                                  CaptureCheckpoint checkpoint, Class<T> responseType,
                                                  Runnable action) {
        Objects.requireNonNull(checkpoint, "An action checkpoint is required");
        Objects.requireNonNull(action, "A browser action is required");
        return readEndpoint(endpoint, method, timeout(additionalSeconds), checkpoint, responseType, action);
    }

    private static <T> T readEndpoint(String endpoint, String method, Duration timeout, CaptureCheckpoint checkpoint,
                                      Type responseType, Runnable action) {
        var match = RequestMatcher.forEndpoint(endpoint, method);
        var entry = awaitResponse(capture(), match, timeout, checkpoint, action,
                "%s response for %s".formatted(method, endpoint), true);
        return CaptureJson.response(entry.responseBody(), responseType);
    }

    private static CapturedRequest awaitResponse(Traffic capture, Predicate<CapturedRequest> match, Duration timeout,
                                                   CaptureCheckpoint checkpoint, Runnable action,
                                                   String description, boolean bodyRequired) {
        var boundary = boundary(checkpoint);
        Predicate<CapturedRequest> ready = entry -> match.test(entry) && acceptableStatus(entry)
                && (!bodyRequired || entry.hasResponseBody());
        boolean afterAction = action != null || checkpoint != null;
        Predicate<CapturedRequest> rejected = entry -> afterAction && match.test(entry) && rejectedStatus(entry)
                && capture.find(null, match.and(NetworkService::inFlight)) == null;
        var actionCompleted = new AtomicBoolean(action == null);
        CapturedRequest settled;
        try {
            if (action != null) {
                settled = capture.waitForResponse(ready.or(rejected), timeout, boundary, () -> {
                    action.run();
                    actionCompleted.set(true);
                });
            } else {
                settled = capture.waitUntil(() -> {
                    CapturedRequest found = capture.find(boundary, ready);
                    return found != null ? found : capture.find(boundary, rejected);
                }, timeout);
            }
        } catch (TimeoutError exception) {
            if (!actionCompleted.get()) {
                throw exception;
            }
            var observed = capture.find(boundary, match.and(entry -> entry.responseBodyUnavailable() != null));
            if (observed == null) {
                observed = capture.find(boundary, match);
            }
            String details = observed == null ? "no matching request started"
                    : responseDetails(observed);
            throw new TimeoutError("Expected " + description + " was not captured (" + details + ")", exception);
        }
        if (!ready.test(settled)) {
            throw new TimeoutError("Expected " + description + " was not captured (" + responseDetails(settled) + ")");
        }
        return settled;
    }

    private static String responseDetails(CapturedRequest entry) {
        if (entry.responseBodyUnavailable() != null) {
            return "status %s, %s".formatted(entry.status(), entry.responseBodyUnavailable());
        }
        if (entry.failure() != null) {
            return "request failed: " + entry.failure();
        }
        return "status %s, completed %s, response body captured %s"
                .formatted(entry.status(), entry.completed(), entry.hasResponseBody());
    }

    private static boolean acceptableStatus(CapturedRequest entry) {
        return entry.completed() && entry.status() != null
                && ((entry.status() >= 200 && entry.status() < 300) || entry.status() == 422);
    }

    private static boolean rejectedStatus(CapturedRequest entry) {
        return entry.completed() && entry.status() != null && entry.status() >= 400 && entry.status() != 401
                && !acceptableStatus(entry);
    }

    private static boolean inFlight(CapturedRequest entry) {
        return !entry.completed() && entry.failure() == null;
    }

    public static <T> T getRequestByUrl(String url, String method, Class<T> type) {
        return getRequestByUrl(url, method, (Type) type, null);
    }

    public static <T> T getRequestByUrl(String url, String method, Class<T> type, CaptureCheckpoint checkpoint) {
        return getRequestByUrl(url, method, (Type) type, checkpoint);
    }

    public static <T> T getRequestByUrl(String url, String method, Type type, CaptureCheckpoint checkpoint) {
        var entry = capture().find(boundary(checkpoint), RequestMatcher.forUrl(url, method));
        return entry == null ? null : CaptureJson.request(entry.requestBody(), type);
    }

    public static <T> T getResponseByUrl(String url, String method, Class<T> type) {
        return getResponseByUrl(url, method, (Type) type, null);
    }

    public static <T> T getResponseByUrl(String url, String method, Class<T> type, CaptureCheckpoint checkpoint) {
        return getResponseByUrl(url, method, (Type) type, checkpoint);
    }

    public static <T> T getResponseByUrl(String url, String method, Type type) {
        return getResponseByUrl(url, method, type, null);
    }

    private static <T> T getResponseByUrl(String url, String method, Type type, CaptureCheckpoint checkpoint) {
        var capture = capture();
        var boundary = boundary(checkpoint);
        var match = RequestMatcher.forUrl(url, method);
        var entry = capture.find(boundary, match.and(CapturedRequest::hasResponseBody));
        if (entry == null) {
            entry = capture.find(boundary, match.and(candidate -> candidate.responseBodyUnavailable() != null));
        }
        return entry == null ? null : readResponse(entry, type);
    }

    private static <T> T readResponse(CapturedRequest entry, Type type) {
        if (entry.responseBodyUnavailable() != null) {
            throw new IllegalStateException(responseDetails(entry));
        }
        return CaptureJson.response(entry.responseBody(), type);
    }

    public static void assertRequestMade(String url) {
        Assertions.assertTrue(capture().find(null, RequestMatcher.forUrl(url, null)) != null,
                () -> "The expected request was not captured for " + url);
    }

    public static void assertRequestNotMade(String url, HttpMethod method) {
        Assertions.assertTrue(capture().find(null, RequestMatcher.forUrl(url, method.name())) == null,
                () -> "An unexpected " + method + " request was captured for " + url);
    }

    public static void blockRequestByUrl(String urlPattern, HttpMethod method) {
        capture().block(Pattern.compile(urlPattern), method);
    }

    public static void clearblockRequestList() {
        var capture = Traffic.find(currentContext());
        if (capture != null) {
            capture.clearBlockedRoutes();
        }
    }

    public static void addRequestFilterWithModifiedResponse(String url, String body, int statusCode, String... headers) {
        if (headers.length % 2 != 0) {
            throw new IllegalArgumentException("Response headers must be supplied as name/value pairs");
        }
        var responseHeaders = new LinkedHashMap<String, String>();
        for (int index = 0; index < headers.length; index += 2) {
            responseHeaders.put(headers[index], headers[index + 1]);
        }
        capture().mock(RequestMatcher.forUrl(url, false), body, statusCode, responseHeaders);
    }

    private static BrowserContext context() {
        var context = currentContext();
        if (context == null) {
            throw new IllegalStateException("No browser context is running on this thread");
        }
        return context;
    }

    private static BrowserContext currentContext() {
        var browser = PlaywrightService.wrappedBrowser();
        return browser == null ? null : browser.getCurrentContext();
    }

    private static Traffic capture() {
        return capture(context());
    }

    private static Traffic capture(BrowserContext context) {
        var capture = Traffic.find(context);
        if (capture == null) {
            throw new IllegalStateException("Network capture is not enabled for the current browser context");
        }
        return capture;
    }

    private static Duration timeout(int additionalSeconds) {
        return Duration.ofSeconds(Settings.timeout().getWaitForAjaxTimeout() + additionalSeconds);
    }

    private static Traffic.Checkpoint boundary(CaptureCheckpoint checkpoint) {
        return checkpoint == null ? null : checkpoint.value;
    }

    public static final class CaptureCheckpoint {
        private final Traffic.Checkpoint value;

        private CaptureCheckpoint(Traffic.Checkpoint value) {
            this.value = value;
        }
    }
}
