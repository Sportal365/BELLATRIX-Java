package solutions.bellatrix.core.configuration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class ConfigurationOverridesTests {
    private JsonObject settings() {
        return JsonParser.parseString("""
                {"executionType":"regular","delay":80,"logging":true,"url":"original",
                 "gridSettings":[],"timeoutSettings":{"pageLoadTimeout":30}}
                """).getAsJsonObject();
    }

    @Test
    void overridesPreserveUnspecifiedEnvironmentSettings() {
        var section = settings();
        var properties = new Properties();
        properties.setProperty("test.settings.webSettings.executionType", "grid");
        properties.setProperty("test.settings.otherSettings.url", "unrelated");
        ConfigurationService.applyOverrides(section, "webSettings", properties);
        assertEquals("grid", section.get("executionType").getAsString());
        assertEquals("original", section.get("url").getAsString());
        assertEquals(30, section.getAsJsonObject("timeoutSettings").get("pageLoadTimeout").getAsInt());
    }

    @Test
    void supportsTypedFieldsNestedTimeoutsAndGridCapabilities() {
        var section = settings();
        var properties = new Properties();
        properties.setProperty("test.settings.webSettings.delay", "0");
        properties.setProperty("test.settings.webSettings.logging", "false");
        properties.setProperty("test.settings.webSettings.timeoutSettings.pageLoadTimeout", "45");
        properties.setProperty("test.settings.webSettings.gridSettings", "[{\"url\":\"http://grid:4444\"}]");
        ConfigurationService.applyOverrides(section, "webSettings", properties);
        var mapped = new Gson().fromJson(section, Settings.class);
        assertEquals(0, mapped.delay);
        assertFalse(mapped.logging);
        assertEquals(45, section.getAsJsonObject("timeoutSettings").get("pageLoadTimeout").getAsInt());
        assertEquals("http://grid:4444", section.getAsJsonArray("gridSettings").get(0).getAsJsonObject().get("url").getAsString());
    }

    @Test
    void rejectsMisspelledPropertiesInsteadOfSilentlyUsingDefaults() {
        var properties = new Properties();
        properties.setProperty("test.settings.webSettings.defaultBrowzer", "chrome");
        assertThrows(IllegalArgumentException.class,
                () -> ConfigurationService.applyOverrides(settings(), "webSettings", properties));
    }

    private static class Settings {
        int delay;
        boolean logging;
    }
}
