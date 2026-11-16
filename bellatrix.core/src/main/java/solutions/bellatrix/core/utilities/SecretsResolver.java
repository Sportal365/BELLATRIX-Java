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
package solutions.bellatrix.core.utilities;

import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public class SecretsResolver {
    private static final ThreadLocal<HashMap<String, String>> CACHED_SECRETS;

    static {
        CACHED_SECRETS = ThreadLocal.withInitial(HashMap::new);
        CACHED_SECRETS.set(new HashMap<>());
    }

    public static String getSecret(Supplier<String> getConfigValue) {
        return getSecret(getConfigValue.get());
    }

    public static String getSecret(String configValue) {
        if (CACHED_SECRETS.get().containsKey(configValue)) {
            return CACHED_SECRETS.get().get(configValue);
        }

        String resolved = resolve(configValue, System::getenv);
        CACHED_SECRETS.get().put(configValue, resolved);
        return resolved;
    }

    static String resolve(String configValue, Function<String, String> environment) {
        Objects.requireNonNull(configValue, "Configuration value must not be null");
        if (!configValue.startsWith("{env_") || !configValue.endsWith("}")) {
            return configValue;
        }
        String name = configValue.substring(5, configValue.length() - 1);
        String value = environment.apply(name);
        if (value == null) {
            value = environment.apply(name.toLowerCase(Locale.ROOT));
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required environment variable is missing or blank: " + name);
        }
        return value;
    }
}
