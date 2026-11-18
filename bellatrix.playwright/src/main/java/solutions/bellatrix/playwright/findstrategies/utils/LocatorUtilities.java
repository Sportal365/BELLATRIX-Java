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

package solutions.bellatrix.playwright.findstrategies.utils;

import solutions.bellatrix.playwright.components.common.webelement.WebElement;

import java.lang.reflect.Method;
import java.util.Arrays;

public final class LocatorUtilities {
    private LocatorUtilities() {
    }

    public static Method getGetMethod(Class<?> type, By by, Object... args) {
        var name = switch (by) {
            case ALT_TEXT -> "getByAltText";
            case LABEL -> "getByLabel";
            case PLACEHOLDER -> "getByPlaceholder";
            case ROLE -> "getByRole";
            case TEST_ID -> "getByTestId";
            case TEXT -> "getByText";
            case TITLE -> "getByTitle";
            case FRAME_LOCATOR, LOCATOR -> type == WebElement.class ? "locate" : "locator";
        };
        return Arrays.stream(type.getMethods())
                .filter(method -> method.getName().equals(name) && accepts(method.getParameterTypes(), args))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported locator arguments for " + type.getSimpleName() + "." + name));
    }

    private static boolean accepts(Class<?>[] types, Object[] args) {
        if (types.length != args.length) {
            return false;
        }
        for (int index = 0; index < types.length; index++) {
            if (args[index] == null ? types[index].isPrimitive() : !types[index].isInstance(args[index])) {
                return false;
            }
        }
        return true;
    }
}
