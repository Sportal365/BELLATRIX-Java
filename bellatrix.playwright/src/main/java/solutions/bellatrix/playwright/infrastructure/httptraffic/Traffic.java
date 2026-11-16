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

package solutions.bellatrix.playwright.infrastructure.httptraffic;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import solutions.bellatrix.core.plugins.FailureCollector;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class Traffic {
    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final long MAX_RETAINED_BODY_BYTES = 24L * 1024 * 1024;
    private static final ThreadLocal<Map<BrowserContext, Traffic>> CONTEXTS =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private final BrowserContext context;
    private final CaptureHistory<Request, Response> history = new CaptureHistory<>(MAX_ENTRIES, MAX_RETAINED_BODY_BYTES);
    private final List<Consumer<CapturedRequest>> responseWaiters = new ArrayList<>();
    private final List<BlockedRoute> blockedRoutes = new ArrayList<>();
    private final List<MockedRoute> mockedRoutes = new ArrayList<>();
    private final Consumer<Request> started = this::start;
    private final Consumer<Response> responded = this::response;
    private final Consumer<Request> finished = this::finish;
    private final Consumer<Request> failed = request -> history.finish(request, null, 0,
            Objects.requireNonNullElse(request.failure(), "The browser request failed"), null);

    private Traffic(BrowserContext context) {
        this.context = context;
        context.onRequest(started);
        context.onResponse(responded);
        context.onRequestFinished(finished);
        context.onRequestFailed(failed);
    }

    public static void install(BrowserContext context) {
        CONTEXTS.get().computeIfAbsent(context, Traffic::new);
    }

    public static void uninstall(BrowserContext context) {
        var contexts = CONTEXTS.get();
        var traffic = contexts.remove(context);
        if (contexts.isEmpty()) {
            CONTEXTS.remove();
        }
        if (traffic != null) {
            traffic.close();
        }
    }

    public static Traffic find(BrowserContext context) {
        return CONTEXTS.get().get(context);
    }

    public Checkpoint checkpoint() {
        return history.checkpoint();
    }

    public void reset() {
        history.reset();
    }

    public List<CapturedRequest> entries(Checkpoint checkpoint) {
        return history.snapshot(checkpoint);
    }

    public CapturedRequest find(Checkpoint checkpoint, Predicate<CapturedRequest> matcher) {
        return history.find(checkpoint, matcher);
    }

    public <T> T waitUntil(Supplier<T> current, Duration timeout) {
        Objects.requireNonNull(timeout, "A network timeout is required");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("Network timeout must be nonnegative");
        }
        var initial = current.get();
        if (initial != null) {
            return initial;
        }
        if (timeout.isZero()) {
            throw new TimeoutError("The expected network traffic was not captured");
        }
        var result = new AtomicReference<T>();
        context.waitForCondition(() -> {
            result.set(current.get());
            return result.get() != null;
        }, new BrowserContext.WaitForConditionOptions().setTimeout(timeout.toNanos() / 1_000_000.0));
        return result.get();
    }

    public CapturedRequest waitForResponse(Predicate<CapturedRequest> matcher, Duration timeout,
                                           Checkpoint checkpoint, Runnable action) {
        long boundary = history.boundary(checkpoint);
        var result = new AtomicReference<CapturedRequest>();
        Consumer<CapturedRequest> waiter = entry -> {
            if (entry.sequence() > boundary && result.get() == null && matcher.test(entry)) {
                result.set(entry);
            }
        };
        responseWaiters.add(waiter);
        try {
            action.run();
            return waitUntil(() -> {
                history.boundary(checkpoint);
                return result.get();
            }, timeout);
        } finally {
            responseWaiters.remove(waiter);
        }
    }

    public void block(Pattern url, HttpMethod method) {
        Consumer<Route> handler = route -> {
            if (method == null || method.name().equals(route.request().method())) {
                route.fulfill(new Route.FulfillOptions().setStatus(407));
            } else {
                route.fallback();
            }
        };
        context.route(url, handler);
        blockedRoutes.add(new BlockedRoute(url, handler));
    }

    public void clearBlockedRoutes() {
        var failures = new FailureCollector();
        for (var route : blockedRoutes) {
            failures.run(() -> context.unroute(route.url(), route.handler()));
        }
        blockedRoutes.clear();
        failures.throwIfAny();
    }

    public void mock(Predicate<String> url, String body, int status, Map<String, String> headers) {
        Consumer<Route> handler = route ->
                route.fulfill(new Route.FulfillOptions().setBody(body).setStatus(status).setHeaders(headers));
        context.route(url, handler);
        mockedRoutes.add(new MockedRoute(url, handler));
    }

    private void start(Request request) {
        String body = request.postData();
        history.start(request, request.url(), request.method(), request.resourceType(), request.headers(),
                body != null && body.length() <= MAX_BODY_BYTES / 2 ? body : null);
    }

    private void response(Response response) {
        history.response(response.request(), response, response.status(), response.headers());
    }

    private void finish(Request request) {
        var exchange = history.exchange(request);
        if (exchange == null || exchange.response() == null) {
            return;
        }
        var entry = exchange.request();
        var response = exchange.response();
        var contentType = entry.responseHeaders().getOrDefault("content-type", "");
        long size = contentLength(entry.responseHeaders());
        String body = null;
        String bodyUnavailable = null;
        if (mayHaveBody(entry)) {
            if (retainBody(entry.resourceType(), contentType)) {
                if (size <= MAX_BODY_BYTES) {
                    try {
                        byte[] bytes = response.body();
                        size = bytes.length;
                        if (size <= MAX_BODY_BYTES) {
                            body = new String(bytes, StandardCharsets.UTF_8);
                        }
                    } catch (PlaywrightException ignored) {
                        bodyUnavailable = "Playwright could not read the completed response body";
                    }
                }
                if (size > MAX_BODY_BYTES) {
                    bodyUnavailable = "Response body exceeds the %d-byte capture limit".formatted(MAX_BODY_BYTES);
                }
            } else {
                bodyUnavailable = "Response body omitted for %s resources with this content type"
                        .formatted(entry.resourceType());
            }
            if (size < 0) {
                try {
                    size = request.sizes().responseBodySize;
                } catch (PlaywrightException ignored) {
                    size = -1;
                }
            }
        }
        var completed = history.finish(request, body, size, null, bodyUnavailable);
        if (completed != null) {
            for (var waiter : List.copyOf(responseWaiters)) {
                waiter.accept(completed);
            }
        }
    }

    private static long contentLength(Map<String, String> headers) {
        try {
            return Long.parseLong(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean mayHaveBody(CapturedRequest entry) {
        int status = entry.status();
        return status >= 200 && status != 204 && status != 205 && (status < 300 || status >= 400)
                && !"HEAD".equals(entry.method());
    }

    private static boolean retainBody(String resourceType, String contentType) {
        boolean data = contentType.contains("json") || contentType.contains("xml") || contentType.startsWith("text/plain");
        return switch (resourceType) {
            case "xhr", "fetch" -> data || contentType.isEmpty()
                    || (contentType.startsWith("text/") && !contentType.startsWith("text/css")
                    && !contentType.contains("javascript"));
            case "document" -> data;
            default -> false;
        };
    }

    private void close() {
        history.close();
        responseWaiters.clear();
        var failures = new FailureCollector();
        failures.run(() -> context.offRequest(started));
        failures.run(() -> context.offResponse(responded));
        failures.run(() -> context.offRequestFinished(finished));
        failures.run(() -> context.offRequestFailed(failed));
        failures.run(this::clearBlockedRoutes);
        for (var route : mockedRoutes) {
            failures.run(() -> context.unroute(route.url(), route.handler()));
        }
        mockedRoutes.clear();
        failures.throwIfAny();
    }

    public record Checkpoint(Object generation, long sequence) {
    }

    private record BlockedRoute(Pattern url, Consumer<Route> handler) {
    }

    private record MockedRoute(Predicate<String> url, Consumer<Route> handler) {
    }
}
