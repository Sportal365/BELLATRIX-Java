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
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.WebError;
import lombok.Getter;
import solutions.bellatrix.core.plugins.FailureCollector;
import solutions.bellatrix.core.utilities.SecretMasker;
import solutions.bellatrix.playwright.configuration.WebSettings;
import solutions.bellatrix.playwright.services.NetworkService;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class WrappedBrowser implements AutoCloseable {
    private static final int MAX_CONSOLE_MESSAGES_PER_TYPE = 1_000;
    private static final int MAX_COMPLETED_RESOURCES = 2_048;
    private static final int MAX_AJAX_PATH_LENGTH = 200;

    private final Playwright playwright;
    @Getter private final Browser browser;
    @Getter private BrowserContext currentContext;
    @Getter private Page currentPage;
    private Page primaryPage;
    private final List<ConsoleEntry> consoleEntries = new ArrayList<>();
    private final Map<String, Integer> consoleCounts = new HashMap<>();
    private final Consumer<ConsoleMessage> consoleListener = message -> {
        if ("error".equals(message.type()) || "warning".equals(message.type())) {
            recordConsoleEntry(new ConsoleEntry(message.type(), message.text(), message.location(), message.timestamp()));
        }
    };
    private final Consumer<WebError> webErrorListener = error -> {
        var location = error.location();
        recordConsoleEntry(new ConsoleEntry("error", error.error(),
                location == null ? null : location.url, System.currentTimeMillis()));
    };
    private final Map<Request, PendingAjax> pendingAjax = new IdentityHashMap<>();
    private final Map<Page, ArrayDeque<CompletedResource>> completedResources = new IdentityHashMap<>();
    private long ajaxSequence;
    private final Consumer<Page> ajaxPageOpened = page -> {
        var mainFrameResources = Collections.newSetFromMap(new IdentityHashMap<Request, Boolean>());
        page.onRequest(request -> {
            if (!request.isNavigationRequest() && request.frame() == page.mainFrame()) {
                mainFrameResources.add(request);
                if ("xhr".equals(request.resourceType())) {
                    pendingAjax.put(request, new PendingAjax(page, ++ajaxSequence));
                }
            }
        });
        page.onResponse(response -> {
            var pending = pendingAjax.get(response.request());
            if (pending != null) {
                pending.status = response.status();
            }
        });
        page.onRequestFinished(request -> {
            pendingAjax.remove(request);
            if (mainFrameResources.remove(request) && request.timing() != null) {
                var resources = completedResources.computeIfAbsent(page, ignored -> new ArrayDeque<>());
                if (resources.size() == MAX_COMPLETED_RESOURCES) {
                    resources.removeFirst();
                }
                resources.addLast(new CompletedResource(request.url().toLowerCase(Locale.ROOT),
                        request.resourceType(), request.timing().startTime));
            }
        });
        page.onRequestFailed(request -> {
            pendingAjax.remove(request);
            mainFrameResources.remove(request);
        });
        page.onClose(closed -> {
            discardPendingAjax(closed);
            completedResources.remove(closed);
            mainFrameResources.clear();
        });
    };

    WrappedBrowser(Playwright playwright, Browser browser) {
        this.playwright = playwright;
        this.browser = browser;
    }

    void openContext(Browser.NewContextOptions options, WebSettings settings) {
        currentContext = browser.newContext(options);
        if (TRACING) {
            currentContext.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        }
        PlaywrightService.applyTimeouts(currentContext, settings.getTimeoutSettings());
        currentContext.onConsoleMessage(consoleListener);
        currentContext.onWebError(webErrorListener);
        currentContext.onPage(ajaxPageOpened);
        if (Boolean.TRUE.equals(settings.getShouldCaptureHttpTraffic())) {
            NetworkService.install(currentContext);
        }
        primaryPage = currentContext.newPage();
        currentPage = primaryPage;
    }

    // -Dtest.trace=true records every test (one chunk each); only a failed test's chunk is written to disk
    private static final boolean TRACING = Boolean.getBoolean("test.trace");

    void endTestTrace(Path failedTestTrace) {
        if (!TRACING || currentContext == null) {
            return;
        }
        var options = new Tracing.StopChunkOptions();
        if (failedTestTrace != null) {
            options.setPath(failedTestTrace);
        }
        currentContext.tracing().stopChunk(options);
    }

    boolean hasOpenContext() {
        return browser.isConnected() && currentContext != null && browser.contexts().contains(currentContext)
                && primaryPage != null && !primaryPage.isClosed() && currentPage != null && !currentPage.isClosed();
    }

    void prepareNextTest() {
        currentPage = primaryPage;
        if (TRACING) {
            currentContext.tracing().startChunk();
        }
        boolean capturing = NetworkService.isCapturing();
        if (capturing) {
            NetworkService.uninstall(currentContext);
        }
        for (Page page : currentContext.pages()) {
            if (page != primaryPage) {
                page.close();
            }
        }
        if (capturing) {
            NetworkService.install(currentContext);
        }
        consoleEntries.clear();
        consoleCounts.clear();
        completedResources.clear();
        pendingAjax.clear();
        ajaxSequence = 0L;
    }

    public void setCurrentPage(Page page) {
        if (page.context() != currentContext || page.isClosed()) {
            throw new IllegalArgumentException("Page must be open in the current browser context");
        }
        currentPage = page;
    }

    public List<String> getConsoleErrors() {
        var errors = new ArrayList<>(consoleEntries.stream()
                .filter(entry -> "error".equals(entry.type())).map(ConsoleEntry::message).toList());
        var omittedErrors = consoleCounts.getOrDefault("error", 0) - errors.size();
        if (omittedErrors > 0) {
            errors.add("Additional console errors omitted: " + omittedErrors);
        }
        return List.copyOf(errors);
    }

    public List<ConsoleEntry> getConsoleEntries() {
        return List.copyOf(consoleEntries);
    }

    public void discardConsoleErrors(Predicate<ConsoleEntry> expectedError) {
        var before = consoleEntries.size();
        consoleEntries.removeIf(entry -> "error".equals(entry.type()) && expectedError.test(entry));
        var removed = before - consoleEntries.size();
        consoleCounts.computeIfPresent("error", (type, count) -> count - removed);
    }

    public boolean hasPendingAjax(Page page) {
        return hasPendingAjax(page, documentStartedAt(page));
    }

    public boolean hasPendingAjax(Page page, double documentStartedAt) {
        return pendingAjax.entrySet().stream().anyMatch(entry -> entry.getValue().page == page
                && entry.getValue().awaited && entry.getValue().startedAt(entry.getKey()) >= documentStartedAt);
    }

    /**
     * The number of the XHR started most recently; XHRs started later get higher numbers.
     */
    public long latestAjaxSequence() {
        return ajaxSequence;
    }

    /**
     * Stops waiting for the page's pending XHRs numbered up to latestSequence. They stay in the pending summary.
     */
    public void stopAwaitingAjax(Page page, long latestSequence) {
        pendingAjax.values().stream()
                .filter(pending -> pending.page == page && pending.sequence <= latestSequence)
                .forEach(pending -> pending.awaited = false);
    }

    public double documentStartedAt(Page page) {
        return ((Number) page.evaluate("() => performance.timeOrigin")).doubleValue();
    }

    public void waitForResource(Page page, String partialUrl, String resourceType, Duration timeout) {
        double documentStartedAt = documentStartedAt(page);
        String url = partialUrl.toLowerCase(Locale.ROOT);
        var options = new BrowserContext.WaitForConditionOptions();
        if (!timeout.isZero()) {
            options.setTimeout(timeout.toMillis());
        }
        currentContext.waitForCondition(() -> {
            if (page.isClosed()) {
                throw new PlaywrightException("Cannot observe completed requests after the browser page closed");
            }
            var resources = completedResources.get(page);
            return resources != null && resources.stream().anyMatch(resource -> resource.startedAt >= documentStartedAt
                    && (resourceType == null || resourceType.equals(resource.type)) && resource.url.contains(url));
        }, options);
    }

    /**
     * Stops counting the XHRs already pending on the current page, so waits for AJAX only cover requests started later.
     */
    public void forgetPendingAjax() {
        discardPendingAjax(currentPage);
    }

    private void discardPendingAjax(Page page) {
        pendingAjax.values().removeIf(pending -> pending.page == page);
    }

    public String pendingAjaxSummary(Page page) {
        double documentStartedAt = documentStartedAt(page);
        var pageRequests = pendingAjax.entrySet().stream()
                .filter(entry -> entry.getValue().page == page && entry.getValue().startedAt(entry.getKey()) >= documentStartedAt)
                .sorted(Comparator.comparingLong(entry -> entry.getValue().sequence))
                .toList();
        var origins = pageRequests.stream().map(entry -> requestOrigin(entry.getKey().url()))
                .distinct().limit(5).sorted().toList();
        var requests = pageRequests.stream().limit(10)
                .map(entry -> entry.getValue().describe(entry.getKey().method(), requestPath(entry.getKey().url())))
                .toList();
        return "%d pending top-level XHR requests on the current page, %d on other pages. Current-page origin sample %s. Request sample %s"
                .formatted(pageRequests.size(), pendingAjax.values().stream().filter(pending -> pending.page != page).count(),
                        origins, requests);
    }

    private static String requestOrigin(String url) {
        try {
            var uri = URI.create(url);
            if (uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                var port = uri.getPort();
                var origin = uri.getScheme() + "://" + uri.getHost() + (port < 0 ? "" : ":" + port);
                return SecretMasker.mask(origin);
            }
        } catch (IllegalArgumentException ignored) {
            return "(unknown origin)";
        }
        return "(unknown origin)";
    }

    static String requestPath(String url) {
        try {
            var uri = URI.create(url);
            if (uri.getHost() == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
                return "(unknown path)";
            }
            String rawPath = uri.getRawPath();
            String decodedPath = uri.getPath();
            if (!rawPath.equals(decodedPath) && !decodedPath.equals(SecretMasker.mask(decodedPath))) {
                return "(redacted path)";
            }
            String path = SecretMasker.mask(rawPath.isEmpty() ? "/" : rawPath);
            return path.length() > MAX_AJAX_PATH_LENGTH ? path.substring(0, MAX_AJAX_PATH_LENGTH) + "..." : path;
        } catch (IllegalArgumentException ignored) {
            return "(unknown path)";
        }
    }

    private void recordConsoleEntry(ConsoleEntry entry) {
        if (consoleCounts.merge(entry.type(), 1, Integer::sum) <= MAX_CONSOLE_MESSAGES_PER_TYPE) {
            consoleEntries.add(new ConsoleEntry(entry.type(), SecretMasker.mask(entry.message()),
                    SecretMasker.mask(entry.location()), entry.timestamp()));
        }
    }

    void closeContext() {
        var context = currentContext;
        primaryPage = null;
        currentPage = null;
        currentContext = null;
        if (context == null) {
            return;
        }

        var failures = new FailureCollector();
        failures.run(() -> NetworkService.uninstall(context));
        failures.run(() -> context.offConsoleMessage(consoleListener));
        failures.run(() -> context.offWebError(webErrorListener));
        failures.run(() -> context.offPage(ajaxPageOpened));
        failures.run(context::close);
        consoleEntries.clear();
        consoleCounts.clear();
        completedResources.clear();
        pendingAjax.clear();
        ajaxSequence = 0L;
        failures.throwIfAny();
    }

    @Override
    public void close() {
        var failures = new FailureCollector();
        failures.run(this::closeContext);
        failures.run(browser::close);
        failures.run(playwright::close);
        failures.throwIfAny();
    }

    public record ConsoleEntry(String type, String message, String location, double timestamp) {
    }

    private record CompletedResource(String url, String type, double startedAt) {
    }

    private static final class PendingAjax {
        private final Page page;
        private final long sequence;
        private final long startedAtNanos = System.nanoTime();
        private final long observedAtMillis = System.currentTimeMillis();
        private Integer status;
        private boolean awaited = true;

        private PendingAjax(Page page, long sequence) {
            this.page = page;
            this.sequence = sequence;
        }

        private double startedAt(Request request) {
            var timing = request.timing();
            return timing == null ? observedAtMillis : timing.startTime;
        }

        private String describe(String method, String path) {
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAtNanos);
            return "#%d %s %s %ds %s".formatted(sequence, method, path, elapsedSeconds,
                    status == null ? "awaiting response headers" : "HTTP " + status);
        }
    }
}
