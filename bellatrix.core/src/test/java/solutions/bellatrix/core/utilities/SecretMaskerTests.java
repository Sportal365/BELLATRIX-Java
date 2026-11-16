package solutions.bellatrix.core.utilities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SecretMaskerTests {
    @Test
    void masksRuntimeApiKeysAndAuthorizationValues() {
        var secrets = SecretMasker.secretValues(Map.of(
                "SMP_WIDGETS_API_KEY", "widget-key",
                "SMP_CONTENT_API_DEFAULT_AUTH", "Basic unit-credential",
                "SERVICE_AUTHORIZATION", "Bearer sample-credential",
                "TEST_WORKERS", "8"));
        assertEquals("[REDACTED] [REDACTED] [REDACTED] 8",
                SecretMasker.mask("widget-key Basic unit-credential Bearer sample-credential 8", secrets));
    }

    @Test
    void removesCredentialsBeforeTheyReachConsoleAndReportingListeners() {
        var secrets = SecretMasker.secretValues(Map.of(
                "sportal_cms_username", "user@example.test",
                "sportal_cms_password", "sample password",
                "SERVICE_TOKEN", "sample password extended",
                "EMPTY_SECRET", "",
                "CMS_URL", "https://cms.example.test"));
        assertEquals("[REDACTED] [REDACTED] [REDACTED] https://cms.example.test",
                SecretMasker.mask("user@example.test sample password sample password extended https://cms.example.test", secrets));
        assertNull(SecretMasker.mask(null, secrets));
    }
}
