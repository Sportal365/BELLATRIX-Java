package solutions.bellatrix.playwright.infrastructure.httptraffic;

import java.net.URI;
import java.util.Objects;
import java.util.function.Predicate;

public final class RequestMatcher {
    private RequestMatcher() {
    }

    public static Predicate<CapturedRequest> forUrl(String url, String method) {
        var urlMatches = forUrl(url, false);
        return entry -> (method == null || method.equalsIgnoreCase(entry.method())) && urlMatches.test(entry.url());
    }

    public static Predicate<CapturedRequest> forEndpoint(String url, String method) {
        var urlMatches = forUrl(url, true);
        return entry -> method.equalsIgnoreCase(entry.method()) && urlMatches.test(entry.url())
                && switch (entry.resourceType()) {
                    case "document", "fetch", "xhr" -> true;
                    default -> false;
                };
    }

    public static Predicate<String> forUrl(String url, boolean requireOrigin) {
        Objects.requireNonNull(url, "A request URL is required");
        var expected = parse(url);
        if (!isHttpOrigin(expected)) {
            if (requireOrigin) {
                throw new IllegalArgumentException("Expected an absolute HTTP endpoint: " + url);
            }
            return actual -> actual.contains(url) || actual.contains(url.replace("%20", "+"));
        }
        return urlToMatch -> {
            var actual = parse(urlToMatch);
            return actual != null && expected.getScheme().equalsIgnoreCase(actual.getScheme())
                    && expected.getHost().equalsIgnoreCase(actual.getHost()) && port(expected) == port(actual)
                    && matchesPath(expected, actual, requireOrigin) && matchesQuery(expected, actual, requireOrigin);
        };
    }

    private static boolean matchesPath(URI expected, URI actual, boolean exact) {
        String expectedPath = path(expected);
        String actualPath = path(actual);
        return actualPath.equals(expectedPath)
                || (!exact && actualPath.startsWith(expectedPath.endsWith("/") ? expectedPath : expectedPath + "/"));
    }

    private static boolean matchesQuery(URI expected, URI actual, boolean exact) {
        String expectedQuery = expected.getRawQuery();
        if (expectedQuery == null) {
            return true;
        }
        String actualQuery = actual.getRawQuery();
        if (actualQuery == null) {
            return false;
        }
        expectedQuery = expectedQuery.replace("%20", "+");
        actualQuery = actualQuery.replace("%20", "+");
        return actualQuery.equals(expectedQuery) || (!exact && (actualQuery.startsWith(expectedQuery + "&")
                || (expectedQuery.endsWith("=") && actualQuery.startsWith(expectedQuery))));
    }

    private static boolean isHttpOrigin(URI uri) {
        return uri != null && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private static URI parse(String url) {
        try {
            return URI.create(url.replace(" ", "%20"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String path(URI uri) {
        String path = uri.getRawPath();
        return path == null || path.isEmpty() ? "/" : path;
    }
}
