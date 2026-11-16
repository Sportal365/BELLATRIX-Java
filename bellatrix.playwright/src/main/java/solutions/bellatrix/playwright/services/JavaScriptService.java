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

package solutions.bellatrix.playwright.services;

import com.microsoft.playwright.Locator;
import solutions.bellatrix.core.utilities.SingletonFactory;
import solutions.bellatrix.playwright.components.Frame;
import solutions.bellatrix.playwright.components.WebComponent;

public class JavaScriptService extends WebService {
    public Object execute(String expression) {
        return wrappedBrowser().getCurrentPage().evaluate(expression);
    }

    public Object execute(Frame frame, String expression) {
        return frame.frameLocator().locator("html").evaluate(expression);
    }

    public Object executeInFrame(String frameName, String expression) {
        var frame = SingletonFactory.getInstance(ComponentCreateService.class).byName(Frame.class, frameName);
        return execute(frame, expression);
    }

    public Object execute(String expression, WebComponent component) {
        return component.evaluate(expression);
    }

    public Object execute(String expression, Locator locator) {
        return locator.evaluate(expression);
    }
}
