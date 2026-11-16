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

import com.google.gson.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.SneakyThrows;
import net.lightbody.bmp.BrowserMobProxyServer;
import net.lightbody.bmp.core.har.Har;
import net.lightbody.bmp.core.har.HarEntry;
import net.lightbody.bmp.proxy.CaptureType;
import org.apache.http.HttpStatus;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.opentest4j.AssertionFailedError;
import org.testng.Assert;
import solutions.bellatrix.core.configuration.ConfigurationService;
import solutions.bellatrix.core.utilities.Log;
import solutions.bellatrix.web.configuration.WebSettings;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;

public class ProxyServer {
    private static final ThreadLocal<BrowserMobProxyServer> PROXY_SERVER = ThreadLocal.withInitial(BrowserMobProxyServer::new);
    private static final ThreadLocal<Integer> PORT = new ThreadLocal<>();
    private static final List<Integer> successHttpStatusesList = Arrays.asList(
            HttpStatus.SC_OK,
            HttpStatus.SC_CREATED,
            HttpStatus.SC_ACCEPTED,
            HttpStatus.SC_NON_AUTHORITATIVE_INFORMATION,
            HttpStatus.SC_NO_CONTENT,
            HttpStatus.SC_RESET_CONTENT,
            HttpStatus.SC_UNPROCESSABLE_ENTITY,
            HttpStatus.SC_PARTIAL_CONTENT,
            HttpStatus.SC_MULTI_STATUS
    );

    private static Gson gson;

    @SneakyThrows
    public static int init() {
        Log.info("Starting Proxy Service...");
        PROXY_SERVER.get().setTrustAllServers(true);
        PROXY_SERVER.get().start(0);
        int port = PROXY_SERVER.get().getPort();
        PROXY_SERVER.get().enableHarCaptureTypes(CaptureType.REQUEST_CONTENT, CaptureType.RESPONSE_CONTENT, CaptureType.REQUEST_HEADERS);
        PORT.set(port);
        Log.info("Proxy Service Started at Port %s".formatted(port));
        gson = new GsonBuilder().registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter()).registerTypeAdapter(OffsetDateTime.class, new OffsetDateTimeTypeAdapter()).registerTypeAdapterFactory(new EmptyObjectTypeAdapterFactory()).create();
        return port;
    }

    public static BrowserMobProxyServer get() {
        return PROXY_SERVER.get();
    }

    public static void newHar(String name) {
        var proxyServer = PROXY_SERVER.get();
        var previousHar = proxyServer.getHar();
        proxyServer.newHar(name);
        if (previousHar != null) {
            previousHar.getLog().getEntries().clear();
        }
    }

    public static void newHar() {
        newHar(null);
    }

    public static CaptureCheckpoint checkpoint() {
        var har = PROXY_SERVER.get().getHar();
        if (har == null) {
            throw new IllegalStateException("Response capture has not started");
        }
        return new CaptureCheckpoint(har, har.getLog().getEntries().size());
    }

    public static final class CaptureCheckpoint {
        private final Har har;
        private final int entryCount;

        private CaptureCheckpoint(Har har, int entryCount) {
            this.har = har;
            this.entryCount = entryCount;
        }
    }

    private static List<HarEntry> capturedEntries(CaptureCheckpoint checkpoint) {
        if (checkpoint == null) {
            return getCapturedEntries();
        }
        if (PROXY_SERVER.get().getHar() != checkpoint.har) {
            throw new IllegalStateException("Response capture was reset after the action checkpoint");
        }
        var entries = new ArrayList<>(getCapturedEntries());
        if (entries.size() < checkpoint.entryCount) {
            throw new IllegalStateException("Response history was cleared after the action checkpoint");
        }
        return entries.subList(checkpoint.entryCount, entries.size());
    }

    public static void compactResponseBodies() {
        var har = PROXY_SERVER.get().getHar();
        if (har == null) {
            return;
        }
        var bodies = new HashMap<String, String>();
        for (var entry : har.getLog().getEntries()) {
            var content = entry.getResponse().getContent();
            if (content != null && content.getText() != null && content.getText().length() >= 65536) {
                String text = content.getText();
                String existing = bodies.putIfAbsent(text, text);
                if (existing != null) {
                    content.setText(existing);
                }
            }
        }
    }

    public static void close() {
        Log.info("Stopping Proxy Service...");
        BrowserMobProxyServer proxyServer = PROXY_SERVER.get();
        if (proxyServer != null) {
            proxyServer.stop();
            PROXY_SERVER.remove();
            PORT.remove();
            Log.info("Proxy Service Stopped.");
        }
    }

    public static List<HarEntry> getCapturedEntries() {
        return PROXY_SERVER.get().getHar().getLog().getEntries();
    }

    public static void assertNoErrorCodes() {
        var harEntries = PROXY_SERVER.get().getHar().getLog().getEntries();
        var areThereErrorCodes = harEntries.stream().anyMatch(r -> r.getResponse().getStatus() > 400 && r.getResponse().getStatus() < 599);

        Assert.assertFalse(areThereErrorCodes);
    }

    public static void assertRequestMade(String url) {
        var harEntries = PROXY_SERVER.get().getHar().getLog().getEntries();
        var areRequestsMade = harEntries.stream().anyMatch(r -> r.getRequest().getUrl().contains(url));
        String simiarRequestsString = getSimilarRequestsString(url, harEntries);
        Assert.assertTrue(areRequestsMade, String.format("The expected url '%s' was not loaded! Similar requests: %s", url, simiarRequestsString));
    }

    public static void assertRequestNotMade(String url, HttpMethod httpMethod) {
        var harEntries = PROXY_SERVER.get().getHar().getLog().getEntries();
        var areRequestsMade = harEntries.stream().anyMatch(r -> r.getRequest().getUrl().contains(url) && r.getRequest().getMethod().equals(httpMethod.toString()));

        Assert.assertFalse(areRequestsMade);
    }

    public static void clearHistory() {
        var previousHar = PROXY_SERVER.get().getHar();
        var oldHarCount = previousHar == null ? 0 : previousHar.getLog().getEntries().size();

        newHar();
        Log.info(String.format("The proxy history with %s entries is cleared!", oldHarCount));
    }

    public static void waitForRequest(WebDriver driver, String requestPartialUrl, HttpMethod httpMethod, int additionalTimeoutInSeconds) {
        waitForRequest(driver, requestPartialUrl, httpMethod, additionalTimeoutInSeconds, null);
    }

    public static void waitForRequest(WebDriver driver, String requestPartialUrl, HttpMethod httpMethod,
                                      int additionalTimeoutInSeconds, CaptureCheckpoint checkpoint) {
        long timeout = ConfigurationService.get(WebSettings.class).getTimeoutSettings().getWaitForAjaxTimeout();
        long sleepInterval = ConfigurationService.get(WebSettings.class).getTimeoutSettings().getSleepInterval();
        var webDriverWait = new WebDriverWait(driver, Duration.ofSeconds(timeout + additionalTimeoutInSeconds), Duration.ofSeconds(sleepInterval));
        waitForRequest(requestPartialUrl, httpMethod, webDriverWait, checkpoint);
    }

    static void waitForRequest(String requestPartialUrl, HttpMethod httpMethod, FluentWait<WebDriver> wait,
                               CaptureCheckpoint checkpoint) {
        try {
            wait.until(d -> {
                var harEntries = capturedEntries(checkpoint);

                return harEntries.stream()
                        .anyMatch(entry -> matchesRequest(entry, requestPartialUrl, httpMethod.toString()));
            });
        } catch (TimeoutException exception) {
            Log.error(String.format("The expected request with URL '%s' is not loaded!", requestPartialUrl));
        }
    }

    public static List<String> waitForRequests(WebDriver driver, List<String> requestPartialUrls, HttpMethod httpMethod, int additionalTimeoutInSeconds) {
        var timeouts = ConfigurationService.get(WebSettings.class).getTimeoutSettings();
        var wait = new WebDriverWait(driver,
                Duration.ofSeconds(timeouts.getWaitForAjaxTimeout() + additionalTimeoutInSeconds),
                Duration.ofSeconds(timeouts.getSleepInterval()));
        return waitForRequests(requestPartialUrls, httpMethod, wait);
    }

    static List<String> waitForRequests(List<String> requestPartialUrls, HttpMethod httpMethod, FluentWait<WebDriver> wait) {
        var missing = new LinkedHashSet<>(requestPartialUrls);
        if (missing.isEmpty()) {
            return List.of();
        }
        try {
            wait.until(driver -> {
                var har = PROXY_SERVER.get().getHar();
                if (har != null) {
                    for (var entry : har.getLog().getEntries()) {
                        var request = entry.getRequest();
                        if (request != null && httpMethod.toString().equals(request.getMethod())) {
                            missing.removeIf(url -> request.getUrl().contains(url));
                        }
                    }
                }
                return missing.isEmpty();
            });
        } catch (TimeoutException exception) {
            Log.error("Requests not observed before the shared timeout: %s", String.join(", ", missing));
        }
        return List.copyOf(missing);
    }

    public static void waitForResponse(WebDriver driver, String requestPartialUrl, HttpMethod httpMethod, int additionalTimeoutInSeconds) {
        waitForResponse(driver, requestPartialUrl, httpMethod, additionalTimeoutInSeconds, null);
    }

    public static void waitForResponse(WebDriver driver, String requestPartialUrl, HttpMethod httpMethod,
                                       int additionalTimeoutInSeconds, CaptureCheckpoint checkpoint) {
        var timeouts = ConfigurationService.get(WebSettings.class).getTimeoutSettings();
        var wait = new WebDriverWait(driver, Duration.ofSeconds(timeouts.getWaitForAjaxTimeout() + additionalTimeoutInSeconds),
                Duration.ofSeconds(timeouts.getSleepInterval()));
        waitForResponse(requestPartialUrl, httpMethod, wait, checkpoint);
    }

    static void waitForResponse(String requestPartialUrl, HttpMethod httpMethod, FluentWait<WebDriver> wait,
                                CaptureCheckpoint checkpoint) {
        List<HarEntry> allHarEntries = new ArrayList<>();
        try {
            wait.until(driver -> {
                allHarEntries.clear();
                allHarEntries.addAll(capturedEntries(checkpoint));
                return allHarEntries.stream().anyMatch(entry -> matchesRequest(entry, requestPartialUrl, httpMethod.toString())
                        && entry.getResponse() != null
                        && successHttpStatusesList.contains(entry.getResponse().getStatus())
                        && (httpMethod == HttpMethod.DELETE || hasResponseBody(entry)));
            });
        } catch (TimeoutException exception) {
            String allUrlsString = getSimilarRequestsString(requestPartialUrl, allHarEntries);
            throw new RuntimeException(String.format("The expected response with request URL %s with method %s is not loaded! \r\nSimilar requests: %s",
                    requestPartialUrl, httpMethod, allUrlsString), exception);
        }
    }

    public static <T> T waitForResponseAtEndpoint(WebDriver driver, String endpoint, HttpMethod method,
                                                   int additionalTimeoutInSeconds, CaptureCheckpoint checkpoint,
                                                   Class<T> responseType) {
        var timeouts = ConfigurationService.get(WebSettings.class).getTimeoutSettings();
        var wait = new WebDriverWait(driver,
                Duration.ofSeconds(timeouts.getWaitForAjaxTimeout() + additionalTimeoutInSeconds),
                Duration.ofSeconds(timeouts.getSleepInterval()));
        return waitForResponseAtEndpoint(endpoint, method, wait, checkpoint, responseType);
    }

    static <T> T waitForResponseAtEndpoint(String endpoint, HttpMethod method, FluentWait<WebDriver> wait,
                                          CaptureCheckpoint checkpoint, Class<T> responseType) {
        java.util.Objects.requireNonNull(checkpoint, "An action checkpoint is required");
        var expected = java.net.URI.create(endpoint);
        HarEntry response = wait.withMessage("No current response for " + method + " " + endpoint).until(driver ->
                capturedEntries(checkpoint).stream()
                        .filter(entry -> matchesEndpoint(entry, expected, method.toString()))
                        .filter(ProxyServer::hasResponseBody)
                        .filter(entry -> successHttpStatusesList.contains(entry.getResponse().getStatus()))
                        .findFirst().orElse(null));
        return gson.fromJson(getDataObject(response.getResponse().getContent().getText()), responseType);
    }

    private static boolean matchesEndpoint(HarEntry entry, java.net.URI expected, String method) {
        var request = entry.getRequest();
        if (request == null || !method.equals(request.getMethod()) || request.getUrl() == null) {
            return false;
        }
        try {
            var actual = java.net.URI.create(request.getUrl());
            return java.util.Objects.equals(expected.getScheme(), actual.getScheme())
                    && java.util.Objects.equals(expected.getRawAuthority(), actual.getRawAuthority())
                    && java.util.Objects.equals(expected.getRawPath(), actual.getRawPath())
                    && (expected.getRawQuery() == null || expected.getRawQuery().equals(actual.getRawQuery()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean matchesRequest(HarEntry entry, String url, String method) {
        var request = entry.getRequest();
        return request != null && method.equals(request.getMethod()) && request.getUrl() != null
                && (request.getUrl().contains(url) || request.getUrl().contains(url.replace("%20", "+")));
    }

    private static boolean hasResponseBody(HarEntry entry) {
        return entry.getResponse() != null && entry.getResponse().getContent() != null && entry.getResponse().getContent().getText() != null
                && !entry.getResponse().getContent().getText().isEmpty();
    }

    private static String getSimilarRequestsString(String requestPartialUrl, List<HarEntry> allHarEntries) {
        try {
            ArrayList<String> allUrls = new ArrayList<>();
            allHarEntries.stream().forEach(e -> {


                URI uri = URI.create(requestPartialUrl);
                if (e.getRequest().getUrl().contains(uri.getHost()) || e.getRequest().getUrl().contains(requestPartialUrl)) {
                    allUrls.add(String.format("[%s]%s[%s]", e.getRequest().getMethod(), e.getRequest().getUrl(), e.getResponse().getStatusText()));
                }
            });
            String allUrlsString = "";
            for (String url : allUrls) {
                allUrlsString += url;
                allUrlsString += "\r\n";
            }
            return allUrlsString;
        } catch (Exception ex) {
            ArrayList<String> allUrls = new ArrayList<>();
            allHarEntries.forEach(e -> allUrls.add(e.getRequest().getUrl()));
            return allUrls.toString();
        }
    }

    public static void assertNoLargeImagesRequested() {
        var harEntries = PROXY_SERVER.get().getHar().getLog().getEntries();
        var areThereLargeImages = harEntries.stream().anyMatch(r -> r.getResponse().getContent().getMimeType().startsWith("image") && r.getResponse().getContent().getSize() > 40000);

        Assert.assertFalse(areThereLargeImages);
    }

    public static <T> T getLastRequest(Class<T> requestModelClass) {
        return getCapturedEntries().stream().map(HarEntry::getRequest).filter(request -> request.getPostData() != null).map(request -> gson.fromJson(request.getPostData().getText(), requestModelClass)).reduce((first, second) -> second).orElse(null);
    }

    public static <T> T getLastResponse(Class<T> responseModelClass) {
        return getCapturedEntries().stream().map(HarEntry::getResponse).filter(response -> response.getContent() != null).map(response -> gson.fromJson(getDataObject(response.getContent().getText()), responseModelClass)).reduce((first, second) -> second).orElse(null);
    }

    public static <T> T getRequestByIndex(int index, Class<T> requestModelClass) {
        var entries = getCapturedEntries();
        var harEntry = entries.get(index);
        String json = harEntry.getRequest().getPostData().getText();
        return gson.fromJson(json, requestModelClass);
    }

    public static <T> T getResponseByIndex(int index, Class<T> responseModelClass) {
        var harEntry = getCapturedEntries().get(index);
        String json = harEntry.getResponse().getContent().getText();
        return gson.fromJson(getDataObject(json), responseModelClass);
    }

    public static <T> T getRequestByUrl(String url, String httpMethod, Class<T> requestModelClass) {
        return getRequestByUrl(url, httpMethod, requestModelClass, null);
    }

    public static <T> T getRequestByUrl(String url, String httpMethod, Class<T> requestModelClass, CaptureCheckpoint checkpoint) {
        var harEntries = capturedEntries(checkpoint);
        var harEntry = harEntries.stream().filter(entry -> matchesRequest(entry, url, httpMethod)).findFirst().orElse(null);
        if (harEntry == null) {
            return null;
        }
        String json = harEntry.getRequest().getPostData().getText();
        try {
            return gson.fromJson(json, requestModelClass);
        } catch (Exception e) {
            throw new RuntimeException("Error occurred while converting json to model. Json was: %s".formatted(json), e);
        }
    }

    public static <T> T getRequestByUrl(String url, String httpMethod, Type modelType) {
        return getRequestByUrl(url, httpMethod, modelType, null);
    }

    public static <T> T getRequestByUrl(String url, String httpMethod, Type modelType, CaptureCheckpoint checkpoint) {
        var harEntries = capturedEntries(checkpoint);
        var harEntry = harEntries.stream().filter(entry -> matchesRequest(entry, url, httpMethod)).findFirst().orElse(null);
        if (harEntry == null) {
            return null;
        }
        String json = harEntry.getRequest().getPostData().getText();
        try {
            return gson.fromJson(json, modelType);
        } catch (Exception e) {
            throw new RuntimeException("Error occurred while converting json to model. Json was: %s".formatted(json), e);
        }
    }

    public static <T> T getResponseByUrl(String url, String httpMethod, Class<T> responseModelClass) {
        return getResponseByUrl(url, httpMethod, responseModelClass, null);
    }

    public static <T> T getResponseByUrl(String url, String httpMethod, Class<T> responseModelClass, CaptureCheckpoint checkpoint) {
        var harEntry = findResponseWithBody(url, httpMethod, checkpoint);
        if (harEntry == null) {
            System.out.println("There is no match!");
            return null;
        }
        String json = harEntry.getResponse().getContent().getText();
        try {
            return gson.fromJson(getDataObject(json), responseModelClass);
        } catch (Exception ex) {
            throw new AssertionFailedError("Cannot get JSON body from the string: " + json + ". Error was: " + ex.getMessage());
        }
    }

    public static <T> T getResponseByUrl(String url, String httpMethod, Type responseModelType) {
        return getResponseByUrl(url, httpMethod, responseModelType, null);
    }

    public static <T> T getResponseByUrl(String url, String httpMethod, Type responseModelType, CaptureCheckpoint checkpoint) {
        var harEntry = findResponseWithBody(url, httpMethod, checkpoint);
        if (harEntry == null) {
            System.out.println("There is no match!");
            return null;
        }
        String json = harEntry.getResponse().getContent().getText();
        try {
            return gson.fromJson(getDataObject(json), responseModelType);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to Serialize Json to Object: \n Entity Type: %s\n Json was: %s".formatted(responseModelType.getTypeName(), json));
        }
    }

    private static HarEntry findResponseWithBody(String url, String httpMethod, CaptureCheckpoint checkpoint) {
        return capturedEntries(checkpoint).stream()
                .filter(entry -> matchesRequest(entry, url, httpMethod))
                .filter(ProxyServer::hasResponseBody)
                .findFirst().orElse(null);
    }

    public static void blockRequestByUrl(String url, HttpMethod httpMethod) {
        PROXY_SERVER.get().blacklistRequests(url, 407, httpMethod.toString());
    }

    public static void blockRequestByUrl(String url) {
        PROXY_SERVER.get().blacklistRequests(url, 407);
    }

    public static void clearblockRequestList() {
        PROXY_SERVER.get().clearBlacklist();
    }

    private static String getDataObject(String jsonString) {
        JsonParser parser = new JsonParser();
        JsonObject jsonObject = null;
        JsonArray jsonArray = null;
        boolean isObject = false;

        try {
            jsonObject = (JsonObject)parser.parse(jsonString);
            isObject = true;
        } catch (NullPointerException nullEx) {
            throw new RuntimeException("JSON data is null. " + nullEx.getMessage());
        } catch (Exception exception) {
            jsonArray = (JsonArray)parser.parse(jsonString);
        }

        JsonObject dataObject = null;
        JsonArray dataArray = null;
        if (isObject) {
            if (jsonObject.has("data")) {
                if (jsonObject.get("data").isJsonObject()) {
                    dataObject = jsonObject.getAsJsonObject("data");
                } else {
                    dataArray = jsonObject.getAsJsonArray("data");
                }
            } else {
                dataObject = jsonObject.getAsJsonObject();
            }
        } else {
            dataArray = jsonArray.getAsJsonArray();
        }

        if (dataObject != null) {
            return dataObject.toString();
        } else if (dataArray != null) {
            return dataArray.toString();
        } else {
            return jsonString;
        }
    }

    /**
     * This method should be used when request must not reach the server and response is immediately returned
     * This implementation will wipe all the request headers and there is a chance to hit CORS or some other edge scenarios.
     * If you need add headers or the response in order to mock the desired request's response
     *
     * @param url      - the URL of the request
     * @param jsonBody - the JSON body of the response
     * @param statusCode - the status code of the response
     */
    public static void addRequestFilterWithModifiedResponse(String url, String jsonBody, int statusCode, String... headers) {
        PROXY_SERVER.get().addRequestFilter((request, contents, messageInfo) -> {
            if (messageInfo.getOriginalUrl().contains(url)) {
                final HttpResponse response = new DefaultFullHttpResponse(
                        request.getProtocolVersion(),
                        HttpResponseStatus.valueOf(statusCode),
                        Unpooled.copiedBuffer(jsonBody.getBytes()));
                for (int i = 0; i < headers.length; i += 2) {
                    response.headers().add(headers[i], headers[i + 1]);
                }
                return response;
            } else {
                throw new RuntimeException("The request is not mocked. The URL is not matching the expected URL.");
            }
        });
    }
}
