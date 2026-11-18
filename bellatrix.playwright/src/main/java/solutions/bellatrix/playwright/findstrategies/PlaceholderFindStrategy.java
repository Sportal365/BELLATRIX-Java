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

package solutions.bellatrix.playwright.findstrategies;

import com.microsoft.playwright.Page;
import lombok.Getter;
import solutions.bellatrix.playwright.components.common.webelement.WebElement;
import solutions.bellatrix.playwright.findstrategies.options.PlaceholderOptions;
import solutions.bellatrix.playwright.findstrategies.utils.By;

import java.util.regex.Pattern;

@Getter
public class PlaceholderFindStrategy extends PatternStrategy {
    public PlaceholderFindStrategy(String text) {
        this.text = text;
    }

    public PlaceholderFindStrategy(Pattern pattern) {
        this.pattern = pattern;
    }

    public PlaceholderFindStrategy(String text, PlaceholderOptions options) {
        this.text = text;
        this.options = options;
    }

    public PlaceholderFindStrategy(Pattern pattern, PlaceholderOptions options) {
        this.pattern = pattern;
        this.options = options;
    }

    @Override
    public WebElement convert(Page page) {
        return get(page, By.PLACEHOLDER, searchValue(), options == null ? null : options.absolute());
    }

    @Override
    public WebElement convert(WebElement locator) {
        return get(locator, By.PLACEHOLDER, searchValue(), options == null ? null : options.relative());
    }
}
