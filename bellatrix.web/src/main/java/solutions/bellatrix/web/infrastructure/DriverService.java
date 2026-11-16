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

import net.lightbody.bmp.client.ClientUtil;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.ie.InternetExplorerOptions;
import org.openqa.selenium.remote.AbstractDriverOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.safari.SafariOptions;
import solutions.bellatrix.core.configuration.ConfigurationService;
import solutions.bellatrix.core.utilities.DebugInformation;
import solutions.bellatrix.core.utilities.Log;
import solutions.bellatrix.core.utilities.SecretsResolver;
import solutions.bellatrix.core.utilities.TimestampBuilder;
import solutions.bellatrix.web.configuration.GridSettings;
import solutions.bellatrix.web.configuration.WebSettings;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

public class DriverService {
    private static final Set<WebDriver> ACTIVE_DRIVERS = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final ThreadLocal<Boolean> DISPOSED = ThreadLocal.withInitial(() -> true);
    private static final ThreadLocal<BrowserConfiguration> BROWSER_CONFIGURATION;
    private static final ThreadLocal<HashMap<String, String>> CUSTOM_DRIVER_OPTIONS;
    private static final ThreadLocal<WebDriver> WRAPPED_DRIVER;
    private static boolean isBuildNameSet = false;
    private static String buildName;

    static {
        CUSTOM_DRIVER_OPTIONS = new ThreadLocal<>();
        CUSTOM_DRIVER_OPTIONS.set(new HashMap<>());
        BROWSER_CONFIGURATION = new ThreadLocal<>();
        WRAPPED_DRIVER = new ThreadLocal<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> ACTIVE_DRIVERS.forEach(driver -> {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
            }
        }), "browser-cleanup"));
    }

    public static HashMap<String, String> getCustomDriverOptions() {
        return CUSTOM_DRIVER_OPTIONS.get();
    }

    public static void addDriverOptions(String key, String value) {
        CUSTOM_DRIVER_OPTIONS.get().put(key, value);
    }

    public static WebDriver getWrappedDriver() {
        return WRAPPED_DRIVER.get();
    }

    public static boolean isSessionUsable() {
        var driver = WRAPPED_DRIVER.get();
        if (driver == null || DISPOSED.get()) {
            return false;
        }
        try {
            driver.getCurrentUrl();
            return true;
        } catch (WebDriverException exception) {
            return false;
        }
    }

    public static void setWrappedDriver(WebDriver driver) {
        WRAPPED_DRIVER.set(driver);
    }

    public static BrowserConfiguration getBrowserConfiguration() {
        return BROWSER_CONFIGURATION.get();
    }

    public static WebDriver start(BrowserConfiguration configuration) {
        if (!DISPOSED.get()) {
            return WRAPPED_DRIVER.get();
        }
        BROWSER_CONFIGURATION.set(configuration);
        WebDriver driver = null;
        try {
            var webSettings = ConfigurationService.get(WebSettings.class);
            var executionType = webSettings.getExecutionType();
            if (executionType.equals("regular")) {
                driver = initializeDriverRegularMode();
            } else if (executionType.equals("grid") || executionType.equals("selenoid")) {
                var gridSettings = webSettings.getGridSettings().stream().filter(g -> g.getProviderName().equals(executionType.toLowerCase())).findFirst();
                assert gridSettings.isPresent() : String.format("The specified execution type '%s' is not declared in the configuration", executionType);
                driver = initializeDriverGridMode(gridSettings.get());
            } else if (executionType.equals("healenium")) {
                var gridSettings = webSettings.getGridSettings().stream().filter(g -> g.getProviderName().equals(executionType.toLowerCase())).findFirst();
                assert gridSettings.isPresent() : String.format("The specified execution type '%s' is not declared in the configuration", executionType);
                driver = initializeDriverGridMode(gridSettings.get());
            } else {
                var gridSettings = webSettings.getGridSettings().stream().filter(g -> g.getProviderName().equals(executionType.toLowerCase())).findFirst();
                assert gridSettings.isPresent() : String.format("The specified execution type '%s' is not declared in the configuration", executionType);
                driver = initializeDriverCloudGridMode(gridSettings.get());
            }

            ACTIVE_DRIVERS.add(driver);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(ConfigurationService.get(WebSettings.class).getTimeoutSettings().getPageLoadTimeout()));
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(ConfigurationService.get(WebSettings.class).getTimeoutSettings().getScriptTimeout()));

            if(getBrowserConfiguration().getHeight() != 0 && getBrowserConfiguration().getWidth() != 0) {
                changeWindowSize(driver);
            }
            else {
                driver.manage().window().maximize();
            }

            Log.info(String.format("Window resized to dimensions: %s", driver.manage().window().getSize().toString()));
            WRAPPED_DRIVER.set(driver);
            DISPOSED.set(false);

            return driver;
        } catch (RuntimeException | Error exception) {
            try {
                releaseDriver(driver);
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            } finally {
                WRAPPED_DRIVER.remove();
                DISPOSED.set(true);
            }
            throw exception;
        }
    }

    private static WebDriver initializeDriverCloudGridMode(GridSettings gridSettings) {
        MutableCapabilities caps = new MutableCapabilities();

        switch (BROWSER_CONFIGURATION.get().getBrowser()) {
            case CHROME_HEADLESS:
            case CHROME: {
                var chromeOptions = new ChromeOptions();
                caps.setCapability(ChromeOptions.CAPABILITY, chromeOptions);
                break;
            }
            case FIREFOX_HEADLESS:
            case FIREFOX: {
                var firefoxOptions = new FirefoxOptions();
                caps.setCapability(ChromeOptions.CAPABILITY, firefoxOptions);
                break;
            }
            case EDGE_HEADLESS:
            case EDGE: {
                var edgeOptions = new EdgeOptions();
                caps.setCapability(ChromeOptions.CAPABILITY, edgeOptions);
                break;
            }
            case SAFARI: {
                var safariOptions = new SafariOptions();
                caps.setCapability(ChromeOptions.CAPABILITY, safariOptions);
                break;
            }
        }

        HashMap<String, Object> options = new HashMap<String, Object>();

        // Anton: maybe this is something else for other clouds, should be tested.
        // If this is the case, we need to have branching per provider name.
        options.put("sessionName", getBrowserConfiguration().getTestName());
        // if (gridSettings.getProviderName() == "browserstack") {
        // options.put("sessionName", getBrowserConfiguration().getTestName());
        // }

        addGridOptions(options, gridSettings);
        caps.setCapability(gridSettings.getOptionsName(), options);
        WebDriver driver = null;
        try {
            var url = getUrl(gridSettings.getUrl());
            driver = new RemoteWebDriver(new URI(url).toURL(), caps);
        } catch (Exception e) {
            DebugInformation.printStackTrace(e);
        }

        return driver;
    }

    private static WebDriver initializeDriverGridMode(GridSettings gridSettings) {
        var configuration = BROWSER_CONFIGURATION.get();
        AbstractDriverOptions<?> options;
        switch (configuration.getBrowser()) {
            case CHROME, CHROME_HEADLESS, CHROME_MOBILE -> {
                var chromeOptions = new ChromeOptions();
                chromeOptions.addArguments("--proxy-bypass-list=news-tracker-sockets.api.staging.sportal365.com");
                if (configuration.getBrowser() == Browser.CHROME_HEADLESS) {
                    chromeOptions.addArguments("--headless=new");
                }
                if (configuration.getBrowser() == Browser.CHROME_MOBILE) {
                    chromeOptions.setExperimentalOption("mobileEmulation", mobileEmulation(configuration.getDeviceName()));
                }
                options = chromeOptions;
            }
            case FIREFOX, FIREFOX_HEADLESS -> {
                var firefoxOptions = new FirefoxOptions();
                if (configuration.getBrowser() == Browser.FIREFOX_HEADLESS) {
                    firefoxOptions.addArguments("-headless");
                }
                options = firefoxOptions;
            }
            case EDGE, EDGE_HEADLESS -> {
                var edgeOptions = new EdgeOptions();
                if (configuration.getBrowser() == Browser.EDGE_HEADLESS) {
                    edgeOptions.addArguments("--headless=new");
                }
                options = edgeOptions;
            }
            case SAFARI -> options = new SafariOptions();
            case INTERNET_EXPLORER -> options = new InternetExplorerOptions();
            default -> throw new IllegalArgumentException("Unsupported Grid browser: " + configuration.getBrowser());
        }
        if (configuration.getPlatform() != null && configuration.getPlatform() != Platform.ANY) {
            options.setPlatformName(configuration.getPlatform().toString());
        }
        if (configuration.getVersion() != 0) {
            options.setBrowserVersion(String.valueOf(configuration.getVersion()));
        }
        addDriverOptions(options);
        addGridOptions(options, gridSettings);
        if (options instanceof ChromiumOptions<?> chromiumOptions) {
            Map<String, Object> prefs = new HashMap<>();
            String capability = options instanceof ChromeOptions ? ChromeOptions.CAPABILITY : EdgeOptions.CAPABILITY;
            if (chromiumOptions.getCapability(capability) instanceof Map<?, ?> vendorOptions
                    && vendorOptions.get("prefs") instanceof Map<?, ?> configuredPrefs) {
                configuredPrefs.forEach((key, value) -> prefs.put(key.toString(), value));
            }
            prefs.put("profile.default_content_setting_values.clipboard", 1);
            chromiumOptions.setExperimentalOption("prefs", prefs);
        }
        options.setAcceptInsecureCerts(true);
        options.setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.ACCEPT);

        boolean captureHttpTraffic = ConfigurationService.get(WebSettings.class).getShouldCaptureHttpTraffic();
        if (captureHttpTraffic) {
            ProxyServer.init();
            options.setProxy(ClientUtil.createSeleniumProxy(ProxyServer.get()));
            ProxyServer.newHar();
        }
        try {
            var driver = new RemoteWebDriver(new URI(getUrl(gridSettings.getUrl())).toURL(), options);
            driver.setFileDetector(new LocalFileDetector());
            return driver;
        } catch (MalformedURLException | URISyntaxException e) {
            if (captureHttpTraffic) ProxyServer.close();
            throw new IllegalArgumentException("Invalid Grid URL", e);
        } catch (RuntimeException e) {
            if (captureHttpTraffic) ProxyServer.close();
            throw e;
        }
    }

    private static WebDriver initializeDriverRegularMode() {
        WebDriver driver = null;
        boolean shouldCaptureHttpTraffic = ConfigurationService.get(WebSettings.class).getShouldCaptureHttpTraffic();

        Proxy proxyConfig = null;
        if (shouldCaptureHttpTraffic) {
            ProxyServer.init();
            proxyConfig = ClientUtil.createSeleniumProxy(ProxyServer.get());
            ProxyServer.newHar();
        }

        switch (BROWSER_CONFIGURATION.get().getBrowser()) {
            case CHROME -> {
                var chromeOptions = new ChromeOptions();
                addDriverOptions(chromeOptions);
                addDriverCapabilities(chromeOptions);
                chromeOptions.addArguments("--proxy-bypass-list=news-tracker-sockets.api.staging.sportal365.com");
                chromeOptions.addArguments("--log-level=3","--remote-allow-origins=*", "--disable-search-engine-choice-screen");
                chromeOptions.setAcceptInsecureCerts(true);
                chromeOptions.setCapability(CapabilityType.UNHANDLED_PROMPT_BEHAVIOUR, UnexpectedAlertBehaviour.ACCEPT);
                System.setProperty("webdriver.chrome.silentOutput", "true");
                if (shouldCaptureHttpTraffic) {
                    chromeOptions.setProxy(proxyConfig);
                }

                driver = new ChromeDriver(chromeOptions);
            }
            case CHROME_HEADLESS -> {
                var chromeHeadlessOptions = new ChromeOptions();
                addDriverOptions(chromeHeadlessOptions);
                chromeHeadlessOptions.setAcceptInsecureCerts(true);
                chromeHeadlessOptions.addArguments("--proxy-bypass-list=news-tracker-sockets.api.staging.sportal365.com");
                chromeHeadlessOptions.addArguments("--log-level=3","--remote-allow-origins=*", "--disable-search-engine-choice-screen");
                chromeHeadlessOptions.setCapability(CapabilityType.UNHANDLED_PROMPT_BEHAVIOUR, UnexpectedAlertBehaviour.ACCEPT);
                chromeHeadlessOptions.addArguments("--headless=new");
                System.setProperty("webdriver.chrome.silentOutput", "true");
                if (shouldCaptureHttpTraffic) chromeHeadlessOptions.setProxy(proxyConfig);

                driver = new ChromeDriver(chromeHeadlessOptions);
            }
            case CHROME_MOBILE -> {
                var chromeHeadlessOptions = new ChromeOptions();
                addDriverOptions(chromeHeadlessOptions);
                chromeHeadlessOptions.setAcceptInsecureCerts(true);
                chromeHeadlessOptions.addArguments("--proxy-bypass-list=news-tracker-sockets.api.staging.sportal365.com");
                chromeHeadlessOptions.addArguments("--log-level=3","--remote-allow-origins=*", "--disable-search-engine-choice-screen");
                chromeHeadlessOptions.setCapability(CapabilityType.UNHANDLED_PROMPT_BEHAVIOUR, UnexpectedAlertBehaviour.ACCEPT);

                chromeHeadlessOptions.setExperimentalOption("mobileEmulation", mobileEmulation(BROWSER_CONFIGURATION.get().getDeviceName()));
                System.setProperty("webdriver.chrome.silentOutput", "true");
                if (shouldCaptureHttpTraffic) chromeHeadlessOptions.setProxy(proxyConfig);

                driver = new TouchableWebDriver(chromeHeadlessOptions);
            }
            case FIREFOX -> {
                var firefoxOptions = new FirefoxOptions();
                addDriverOptions(firefoxOptions);
                firefoxOptions.setAcceptInsecureCerts(true);
                firefoxOptions.addPreference("network.proxy.no_proxies_on", "news-tracker-sockets.api.staging.sportal365.com");
                if (shouldCaptureHttpTraffic) firefoxOptions.setProxy(proxyConfig);
                driver = new FirefoxDriver(firefoxOptions);
            }
            case FIREFOX_HEADLESS -> {
                var firefoxHeadlessOptions = new FirefoxOptions();
                addDriverOptions(firefoxHeadlessOptions);
                firefoxHeadlessOptions.setAcceptInsecureCerts(true);
                firefoxHeadlessOptions.addPreference("network.proxy.no_proxies_on", "news-tracker-sockets.api.staging.sportal365.com");
                firefoxHeadlessOptions.addArguments("--headless");
                if (shouldCaptureHttpTraffic) firefoxHeadlessOptions.setProxy(proxyConfig);
                driver = new FirefoxDriver(firefoxHeadlessOptions);
            }
            case EDGE -> {
                var edgeOptions = new EdgeOptions();
                edgeOptions.addArguments("--proxy-bypass-list=news-tracker-sockets.api.staging.sportal365.com");
                addDriverOptions(edgeOptions);
                if (shouldCaptureHttpTraffic) edgeOptions.setProxy(proxyConfig);
                driver = new EdgeDriver(edgeOptions);
            }
            case EDGE_HEADLESS -> {
                var edgeOptions = new EdgeOptions();
                edgeOptions.addArguments("--proxy-bypass-list=news-tracker-sockets.api.staging.sportal365.com");
                edgeOptions.addArguments("--headless");
                edgeOptions.addArguments("--disable-gpu");
                addDriverOptions(edgeOptions);
                if (shouldCaptureHttpTraffic) edgeOptions.setProxy(proxyConfig);
                driver = new EdgeDriver(edgeOptions);
            }
            case SAFARI -> {
                System.setProperty("webdriver.safari.driver", "/usr/bin/safaridriver");
                var safariOptions = new SafariOptions();
                addDriverOptions(safariOptions);
                if (shouldCaptureHttpTraffic) safariOptions.setProxy(proxyConfig);
                driver = new SafariDriver(safariOptions);
            }
            case INTERNET_EXPLORER -> {
                var internetExplorerOptions = new InternetExplorerOptions();
                addDriverOptions(internetExplorerOptions);
                internetExplorerOptions.introduceFlakinessByIgnoringSecurityDomains().ignoreZoomSettings();
                if (shouldCaptureHttpTraffic) internetExplorerOptions.setProxy(proxyConfig);
                driver = new InternetExplorerDriver(internetExplorerOptions);
            }
        }

        return driver;
    }

    private static DesiredCapabilities addDriverCapabilities(ChromeOptions chromeOptions) {
        DesiredCapabilities caps = new DesiredCapabilities();
        // INIT CHROME OPTIONS
        Map<String, Object> prefs = new HashMap<String, Object>();
        Map<String, Object> profile = new HashMap<String, Object>();
        Map<String, Object> contentSettings = new HashMap<String, Object>();

        // SET CHROME OPTIONS
        // 0 - Default, 1 - Allow, 2 - Block
        contentSettings.put("notifications", 1);
        profile.put("managed_default_content_settings", contentSettings);
        prefs.put("profile", profile);
        chromeOptions.setExperimentalOption("prefs", prefs);

        // SET CAPABILITY
        caps.setCapability(ChromeOptions.CAPABILITY, chromeOptions);
        return caps;
    }

    private static <TOption extends MutableCapabilities> void addGridOptions(HashMap<String, Object> options, GridSettings gridSettings) {
        Log.info("Add WebDriver Options:");
        Log.info("");
        for (var entry : gridSettings.getArguments()) {
            for (var c : entry.entrySet()) {
                if (c.getKey().toLowerCase().contains("build")) {
                    var buildName = getBuildName();
                    if (buildName == null) {
                        buildName = c.getValue().toString();
                    }

                    options.put(c.getKey(), buildName);
                    Log.info(c.getKey() + " = " + buildName);
                } else {
                    if (c.getValue() instanceof String && c.getValue().toString().startsWith("{env_")) {
                        var envValue = SecretsResolver.getSecret(c.getValue().toString());
                        options.put(c.getKey(), envValue);
                        Log.info(c.getKey() + " = " + envValue);
                    } else {
                        options.put(c.getKey(), c.getValue());
                        Log.info(c.getKey() + " = " + c.getValue());
                    }
                }
            }

            if ("lambdatest".equalsIgnoreCase(gridSettings.getProviderName())) {
                options.put("lambdaMaskCommands", new String[]{"setValues", "setCookies", "getCookies"});

                try {
                    var usernameSecret = gridSettings.getArguments().get(0).get("username").toString();
                    var accessKeySecret = gridSettings.getArguments().get(0).get("accessKey").toString();
                    var usernameValue = SecretsResolver.getSecret(usernameSecret);
                    var accessKeyValue = SecretsResolver.getSecret(accessKeySecret);

                    var res = given().auth().preemptive().basic(usernameValue, accessKeyValue)
                            .get("https://api.lambdatest.com/automation/api/v1/user-files");

                    options.put("lambda:userFiles", res.body().jsonPath().getList("data.key"));
                } catch (Exception e) {
                    DebugInformation.printStackTrace(e);
                }


            }

            Log.info("");
        }
    }

    private static <TOption extends MutableCapabilities> void addGridOptions(TOption options, GridSettings gridSettings) {
        for (var entry : gridSettings.getArguments()) {
            for (var c : entry.entrySet()) {
                if (c.getValue() instanceof String && c.getValue().toString().startsWith("{env_")) {
                    var envValue = SecretsResolver.getSecret(c.getValue().toString());
                    options.setCapability(c.getKey(), envValue);
                } else {
                    options.setCapability(c.getKey(), c.getValue());
                }
            }
        }

        if ("lambdatest".equalsIgnoreCase(gridSettings.getProviderName())) {
            options.setCapability("lambdaMaskCommands", new String[]{"setValues", "setCookies", "getCookies"});
        }
    }

    private static <TOption extends MutableCapabilities> void addDriverOptions(TOption chromeOptions) {
        for (var optionKey : BROWSER_CONFIGURATION.get().driverOptions.keySet()) {
            chromeOptions.setCapability(optionKey, BROWSER_CONFIGURATION.get().driverOptions.get(optionKey));
        }
    }

    private static void changeWindowSize(WebDriver wrappedDriver) {
        try {
            if (getBrowserConfiguration().getHeight() != 0 && getBrowserConfiguration().getWidth() != 0) {
                Log.info(String.format("Setting window size to %sx%s",getBrowserConfiguration().getWidth(), getBrowserConfiguration().getHeight()));
                wrappedDriver.manage().window().setSize(new Dimension(getBrowserConfiguration().getWidth(), getBrowserConfiguration().getHeight()));
            }
        } catch (Exception ex) { System.out.println("Error while resizing browser window: " + ex.getMessage());}
    }

    private static String getBuildName() {
        if (!isBuildNameSet) {
            buildName = System.getProperty("buildName");
        }

        if (buildName == null) {
            InputStream input = ConfigurationService.class.getResourceAsStream("/application.properties");
            var p = new Properties();
            try {
                p.load(input);
            } catch (IOException e) {
                return null;
            }

            if (!isBuildNameSet) {
                buildName = p.getProperty("buildName");
            }

            if (buildName.equals("{randomNumber}") && !isBuildNameSet) {
                buildName = TimestampBuilder.buildUniqueTextByPrefix("LE_");
                isBuildNameSet = true;
            }
        }

        return buildName;
    }

    private static String getUrl(String url) {
        String result = url;
        if (url.startsWith("{env_")) {
            result = SecretsResolver.getSecret(url);
        } else if (url.contains("{env_")) {
            String pattern = "\\{env_.*?\\}";
            Pattern compiledPattern = Pattern.compile(pattern);
            Matcher matcher = compiledPattern.matcher(url);
            List<String> allMatches = new ArrayList<String>();

            while (matcher.find()) {
                allMatches.add(matcher.group());
            }

            for (String match : allMatches) {
                result = result.replace(match, SecretsResolver.getSecret(match));
            }
        }

        return result;
    }

    private static Map<String, Object> mobileEmulation(DeviceName device) {
        if (device == null || device == DeviceName.NOT_SET) {
            throw new IllegalArgumentException("Mobile Chrome requires a device profile");
        }
        Map<String, Object> deviceMetrics = new HashMap<>();
        deviceMetrics.put("width", device.getWidth());
        deviceMetrics.put("height", device.getHeight());
        deviceMetrics.put("pixelRatio", device.getScaleFactor());
        Map<String, Object> mobileEmulation = new HashMap<>();
        mobileEmulation.put("deviceMetrics", deviceMetrics);
        mobileEmulation.put("userAgent", "Mozilla/5.0 (Linux; Android 4.2.1; en-us; Nexus 5 Build/JOP40D) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.166 Mobile Safari/535.19");
        return mobileEmulation;
    }

    private static void releaseDriver(WebDriver driver) {
        try {
            if (driver != null) {
                driver.quit();
            }
        } catch (NoSuchSessionException ignored) {
        } finally {
            if (driver != null) {
                ACTIVE_DRIVERS.remove(driver);
            }
            if (ProxyServer.get().isStarted()) {
                ProxyServer.close();
            }
        }
    }

    public static void close() {
        if (DISPOSED.get()) {
            return;
        }

        var driver = WRAPPED_DRIVER.get();
        WRAPPED_DRIVER.remove();
        DISPOSED.set(true);
        if (CUSTOM_DRIVER_OPTIONS.get() != null) {
            CUSTOM_DRIVER_OPTIONS.get().clear();
        }
        releaseDriver(driver);
    }
}
