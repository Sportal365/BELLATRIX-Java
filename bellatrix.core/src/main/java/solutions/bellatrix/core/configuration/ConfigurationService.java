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

package solutions.bellatrix.core.configuration;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import lombok.experimental.UtilityClass;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

@UtilityClass
public final class ConfigurationService {
    private static String environment;

    public static String getEnvironment() {
        return environment;
    }

    public static <T> T get(Class<T> configSection) {
        T mappedObject = (T)new Object();
        if (environment == null) {
            String environmentOverride = System.getProperty("environment");
            if (environmentOverride == null) {
                var p = new Properties();
                try (var input = openResource("application.properties")) {
                    p.load(input);
                } catch (IOException e) {
                    throw new IllegalStateException("Could not read configuration resource: application.properties", e);
                }

                environment = p.getProperty("environment");
            } else {
                environment = environmentOverride;
            }
        }

        String fileName = String.format("testFrameworkSettings.%s.json", environment);
        String jsonFileContent = getFileAsString(fileName);
        String sectionName = getSectionName(configSection);

        var section = JsonParser.parseString(jsonFileContent).getAsJsonObject().getAsJsonObject(sectionName);
        applyOverrides(section, sectionName, System.getProperties());
        var jsonObject = section.toString();

        var gson = new Gson();

        try {
            mappedObject = gson.fromJson(jsonObject, configSection);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return mappedObject;
    }

    static void applyOverrides(com.google.gson.JsonObject section, String sectionName, Properties properties) {
        String prefix = "test.settings." + sectionName + ".";
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String[] path = key.substring(prefix.length()).split("\\.");
            var target = section;
            for (int i = 0; i < path.length - 1; i++) {
                if (!target.has(path[i]) || !target.get(path[i]).isJsonObject()) {
                    throw new IllegalArgumentException("Unknown configuration override: " + key);
                }
                target = target.getAsJsonObject(path[i]);
            }
            String name = path[path.length - 1];
            if (!target.has(name)) {
                throw new IllegalArgumentException("Unknown configuration override: " + key);
            }
            String value = properties.getProperty(key);
            if (target.get(name).isJsonArray() || target.get(name).isJsonObject()) {
                target.add(name, JsonParser.parseString(value));
            } else {
                target.addProperty(name, value);
            }
        }
    }

    public static String getSectionName(Class<?> configSection) {
        var sb = new StringBuilder(configSection.getSimpleName());
        sb.setCharAt(0, Character.toLowerCase(sb.charAt(0)));
        return sb.toString();
    }

    public static String getFileAsString(String fileName) {
        try (var input = openResource(fileName)) {
            return IOUtils.toString(input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read configuration resource: " + fileName, e);
        }
    }

    private static InputStream openResource(String fileName) {
        var input = ConfigurationService.class.getResourceAsStream("/" + fileName);
        if (input == null) {
            throw new IllegalStateException("Missing configuration resource: " + fileName);
        }
        return input;
    }
}
