package solutions.bellatrix.core.utilities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SecretsResolverTests {
    @Test
    void exactEnvironmentNameTakesPrecedence() {
        var variables = Map.of("CMS_BASE_URL", "exact", "cms_base_url", "legacy");
        assertEquals("exact", SecretsResolver.resolve("{env_CMS_BASE_URL}", variables::get));
    }

    @Test
    void existingLowercaseEnvironmentNamesRemainSupported() {
        var variables = Map.of("cms_base_url", "legacy");
        assertEquals("legacy", SecretsResolver.resolve("{env_CMS_BASE_URL}", variables::get));
    }

    @Test
    void missingAndBlankVariablesFailWithoutExposingValues() {
        for (String value : new String[]{null, "", "   "}) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> SecretsResolver.resolve("{env_REQUIRED_TOKEN}", name -> value));
            assertEquals("Required environment variable is missing or blank: REQUIRED_TOKEN", failure.getMessage());
        }
    }

    @Test
    void blankExactVariableDoesNotSilentlyUseAnotherValue() {
        var variables = Map.of("TOKEN", "", "token", "legacy");
        assertThrows(IllegalStateException.class,
                () -> SecretsResolver.resolve("{env_TOKEN}", variables::get));
    }

    @Test
    void literalConfigurationDoesNotAccessEnvironment() {
        assertEquals("https://example.test/env_settings", SecretsResolver.resolve("https://example.test/env_settings",
                name -> { throw new AssertionError("Unexpected environment lookup"); }));
    }
}
