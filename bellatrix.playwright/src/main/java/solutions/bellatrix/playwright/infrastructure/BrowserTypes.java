/*
 * Copyright 2024 Automate The Planet Ltd.
 * Author: Miriam Kyoseva
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.bellatrix.playwright.infrastructure;

import lombok.Getter;

import java.util.Arrays;

@Getter
public enum BrowserTypes {
    CHROMIUM("chromium"),
    CHROMIUM_HEADLESS("chromium_headless"),
    CHROME("chrome"),
    CHROME_HEADLESS("chrome_headless"),
    FIREFOX("firefox"),
    FIREFOX_HEADLESS("firefox_headless"),
    EDGE("edge"),
    EDGE_HEADLESS("edge_headless"),
    WEBKIT("webkit"),
    WEBKIT_HEADLESS("webkit_headless");

    private final String value;
    BrowserTypes(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }

    public static BrowserTypes fromText(String text) {
        return Arrays.stream(values())
                .filter(l -> l.value.equalsIgnoreCase(text))
                .findFirst().orElse(BrowserTypes.CHROME);
    }
}