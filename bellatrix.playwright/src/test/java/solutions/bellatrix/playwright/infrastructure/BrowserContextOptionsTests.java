package solutions.bellatrix.playwright.infrastructure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import solutions.bellatrix.playwright.configuration.ContextSettings;
import solutions.bellatrix.playwright.configuration.WebSettings;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class BrowserContextOptionsTests {
    @Test
    void appliesExplicitContextSettingsAndBrowserDimensions() {
        var settings = new WebSettings();
        settings.setDefaultBrowserWidth(1920);
        settings.setDefaultBrowserHeight(1080);
        var context = new ContextSettings();
        context.setShouldSetContextSettings(true);
        context.setJsEnabled(true);
        context.setLocale("en-GB");
        context.setTimezoneId("Europe/Sofia");
        context.setPermissions(List.of("geolocation"));
        settings.setContextSettings(context);
        var configuration = new BrowserConfiguration(BrowserTypes.CHROMIUM_HEADLESS, Lifecycle.REUSE_IF_STARTED);
        configuration.setWidth(800);
        configuration.setHeight(600);

        var options = PlaywrightService.contextOptions(configuration, settings);

        assertEquals(800, options.viewportSize.orElseThrow().width);
        assertEquals(600, options.viewportSize.orElseThrow().height);
        assertEquals("en-GB", options.locale);
        assertEquals("Europe/Sofia", options.timezoneId);
        assertEquals(List.of("geolocation"), options.permissions);
        assertTrue(options.javaScriptEnabled);
        assertNull(options.storageState);
        assertNull(options.storageStatePath);
    }

    @Test
    void appliesWidgetDeviceViewportAndScale() {
        var configuration = new BrowserConfiguration(BrowserTypes.CHROME_MOBILE, Lifecycle.REUSE_IF_STARTED);
        configuration.setDeviceName(DeviceName.IPHONE_12_PRO_MOBILE);

        var options = PlaywrightService.contextOptions(configuration, new WebSettings());

        assertEquals(390, options.viewportSize.orElseThrow().width);
        assertEquals(844, options.viewportSize.orElseThrow().height);
        assertEquals(2.0, options.deviceScaleFactor);
        assertTrue(options.hasTouch);
        assertTrue(options.isMobile);
    }
}
