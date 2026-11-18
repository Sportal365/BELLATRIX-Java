package solutions.bellatrix.playwright.infrastructure.httptraffic;

import java.time.Instant;
import java.util.Map;

public record CapturedRequest(long sequence, Instant startedAt, String url, String method, String resourceType,
                              Map<String, String> requestHeaders, String requestBody, Integer status,
                              Map<String, String> responseHeaders, String responseBody, long responseSize,
                              boolean completed, String failure, String responseBodyUnavailable) {
    public CapturedRequest {
        requestHeaders = Map.copyOf(requestHeaders);
        responseHeaders = Map.copyOf(responseHeaders);
    }

    public boolean hasResponseBody() {
        return completed && responseBody != null && !responseBody.isEmpty();
    }

    CapturedRequest withResponse(int status, Map<String, String> headers) {
        return new CapturedRequest(sequence, startedAt, url, method, resourceType, requestHeaders, requestBody,
                status, headers, null, 0, false, null, null);
    }

    CapturedRequest finish(String body, long size, String failure, String bodyUnavailable) {
        return new CapturedRequest(sequence, startedAt, url, method, resourceType, requestHeaders, requestBody,
                status, responseHeaders, body, size, failure == null, failure, bodyUnavailable);
    }
}
