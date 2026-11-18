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
import com.microsoft.playwright.options.AriaRole;
import lombok.Getter;
import solutions.bellatrix.core.utilities.ConverterService;
import solutions.bellatrix.playwright.components.common.webelement.WebElement;
import solutions.bellatrix.playwright.components.common.webelement.options.GetByRoleOptions;
import solutions.bellatrix.playwright.findstrategies.options.RoleOptions;
import solutions.bellatrix.playwright.infrastructure.PlaywrightService;

@Getter
public class AriaRoleFindStrategy extends FindStrategy {
    private final AriaRole role;
    public AriaRoleFindStrategy(AriaRole role) {
        this(role, RoleOptions.createAbsolute(null));
    }
    public AriaRoleFindStrategy(AriaRole role, RoleOptions options) {
        this.role = role;
        this.options = options;
    }


    @Override
    public WebElement convert(Page page) {
        webElement = new WebElement(page.getByRole(role, ((RoleOptions) options).absolute()));
        return webElement;
    }

    @Override
    public WebElement convert(WebElement locator) {
        if (locator == null) {
            return convert(PlaywrightService.wrappedBrowser().getCurrentPage());
        }
        var relativeOptions = ConverterService.convertToClass(((RoleOptions) options).relative(), GetByRoleOptions.class);
        webElement = locator.getByRole(role, relativeOptions);
        return webElement;
    }
}
