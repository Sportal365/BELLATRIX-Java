package solutions.bellatrix.playwright.infrastructure.httptraffic;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class CaptureHistory<K, R> {
    private final Map<K, Exchange<R>> entries = new LinkedHashMap<>();
    private final int entryLimit;
    private final long bodyLimit;
    private Object generation = new Object();
    private long sequence;
    private long evictedThrough;
    private long bodySize;
    private boolean closed;

    CaptureHistory(int entryLimit, long bodyLimit) {
        this.entryLimit = entryLimit;
        this.bodyLimit = bodyLimit;
    }

    void start(K key, String url, String method, String resourceType, Map<String, String> headers, String body) {
        if (closed) {
            return;
        }
        var entry = new CapturedRequest(++sequence, Instant.now(), url, method, resourceType, headers, body, null,
                Map.of(), null, 0, false, null, null);
        entries.put(key, new Exchange<>(entry, null));
        bodySize += bodySize(body);
        evictOldEntries();
    }

    void response(K key, R response, int status, Map<String, String> headers) {
        entries.computeIfPresent(key, (ignored, exchange) ->
                new Exchange<>(exchange.request().withResponse(status, headers), response));
    }

    CapturedRequest finish(K key, String body, long size, String failure, String bodyUnavailable) {
        var exchange = entries.get(key);
        if (exchange == null) {
            return null;
        }
        var entry = exchange.request().finish(body, size, failure, bodyUnavailable);
        bodySize += bodySize(body) - bodySize(exchange.request().responseBody());
        entries.put(key, new Exchange<>(entry, exchange.response()));
        evictOldEntries();
        return entries.containsKey(key) ? entry : null;
    }

    Exchange<R> exchange(K key) {
        return entries.get(key);
    }

    List<CapturedRequest> snapshot(Traffic.Checkpoint checkpoint) {
        long after = boundary(checkpoint);
        return entries.values().stream().map(Exchange::request).filter(entry -> entry.sequence() > after).toList();
    }

    CapturedRequest find(Traffic.Checkpoint checkpoint, Predicate<CapturedRequest> matcher) {
        long after = boundary(checkpoint);
        for (var exchange : entries.values()) {
            var entry = exchange.request();
            if (entry.sequence() > after && matcher.test(entry)) {
                return entry;
            }
        }
        return null;
    }

    Traffic.Checkpoint checkpoint() {
        boundary(null);
        return new Traffic.Checkpoint(generation, sequence);
    }

    long boundary(Traffic.Checkpoint checkpoint) {
        if (closed) {
            throw new IllegalStateException("Network capture has closed");
        }
        if (checkpoint == null) {
            return evictedThrough;
        }
        if (checkpoint.generation() != generation) {
            throw new IllegalStateException("Network capture was reset or replaced after the action checkpoint");
        }
        if (checkpoint.sequence() < evictedThrough) {
            throw new IllegalStateException("The action checkpoint predates retained network history");
        }
        return checkpoint.sequence();
    }

    void reset() {
        boundary(null);
        entries.clear();
        bodySize = 0;
        evictedThrough = sequence;
        generation = new Object();
    }

    void close() {
        reset();
        closed = true;
    }

    private void evictOldEntries() {
        var iterator = entries.values().iterator();
        while (entries.size() > entryLimit || bodySize > bodyLimit) {
            var entry = iterator.next().request();
            evictedThrough = entry.sequence();
            bodySize -= bodySize(entry.requestBody()) + bodySize(entry.responseBody());
            iterator.remove();
        }
    }

    private static long bodySize(String body) {
        return body == null ? 0 : 2L * body.length();
    }

    record Exchange<R>(CapturedRequest request, R response) {
    }
}
