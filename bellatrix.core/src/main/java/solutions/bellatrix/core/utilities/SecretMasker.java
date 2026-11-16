package solutions.bellatrix.core.utilities;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SecretMasker {
    private static final List<String> SECRETS = secretValues(System.getenv());

    private SecretMasker() { }

    public static String mask(String text) {
        return mask(text, SECRETS);
    }

    static List<String> secretValues(Map<String, String> environment) {
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT)
                        .matches(".*(password|username|token|secret|authorization|api_key|_auth$).*"))
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    static String mask(String text, List<String> secrets) {
        if (text == null) {
            return null;
        }
        for (String secret : secrets) {
            text = text.replace(secret, "[REDACTED]");
        }
        return text;
    }
}
