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

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import solutions.bellatrix.core.utilities.ConverterService;
import solutions.bellatrix.playwright.findstrategies.options.Options;
import solutions.bellatrix.playwright.findstrategies.utils.By;
import solutions.bellatrix.playwright.findstrategies.utils.LocatorUtilities;
import solutions.bellatrix.playwright.infrastructure.PlaywrightService;
import solutions.bellatrix.playwright.components.common.webelement.FrameElement;
import solutions.bellatrix.playwright.components.common.webelement.WebElement;
import solutions.bellatrix.playwright.components.common.webelement.options.GetByAltTextOptions;
import solutions.bellatrix.playwright.components.common.webelement.options.GetByLabelOptions;
import solutions.bellatrix.playwright.components.common.webelement.options.GetByPlaceholderOptions;
import solutions.bellatrix.playwright.components.common.webelement.options.GetByRoleOptions;
import solutions.bellatrix.playwright.components.common.webelement.options.GetByTextOptions;
import solutions.bellatrix.playwright.components.common.webelement.options.GetByTitleOptions;

import java.util.Arrays;
import java.util.Map;

@Getter @Setter
public abstract class FindStrategy implements Cloneable {
    private static final Map<By, Class<?>> RELATIVE_OPTIONS = Map.of(
            By.ALT_TEXT, GetByAltTextOptions.class,
            By.LABEL, GetByLabelOptions.class,
            By.PLACEHOLDER, GetByPlaceholderOptions.class,
            By.ROLE, GetByRoleOptions.class,
            By.TEXT, GetByTextOptions.class,
            By.TITLE, GetByTitleOptions.class);

    protected Options options;
    protected WebElement webElement;

    public abstract WebElement convert(Page page);
    public abstract WebElement convert(WebElement locator);

    @SneakyThrows
    protected WebElement get(Page page, By by, Object... args) {
        var arguments = omitAbsentOptions(args);
        var method = LocatorUtilities.getGetMethod(Page.class, by, arguments);
        var locator = (Locator) method.invoke(page, arguments);
        webElement = by == By.FRAME_LOCATOR ? new FrameElement(locator) : new WebElement(locator);
        return webElement;
    }

    @SneakyThrows
    protected WebElement get(WebElement baseLocator, By by, Object... args) {
        if (baseLocator == null) {
            return convert(PlaywrightService.wrappedBrowser().getCurrentPage());
        }

        var arguments = omitAbsentOptions(args);
        var optionsType = RELATIVE_OPTIONS.get(by);
        if (arguments.length == 2 && optionsType != null && !optionsType.isInstance(arguments[1])) {
            arguments = new Object[]{arguments[0], ConverterService.convertToClass(arguments[1], optionsType)};
        }

        var method = LocatorUtilities.getGetMethod(WebElement.class, by, arguments);
        var element = (WebElement) method.invoke(baseLocator, arguments);
        webElement = by == By.FRAME_LOCATOR ? new FrameElement(element) : element;
        return webElement;
    }

    private static Object[] omitAbsentOptions(Object[] args) {
        if (args.length == 2 && args[1] == null) {
            return Arrays.copyOf(args, 1);
        }
        return args;
    }

    @Override
    public String toString() {
        return webElement.toString();
    }

    public String getValue() {
        return toString();
    }

    @Override
    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }
}
