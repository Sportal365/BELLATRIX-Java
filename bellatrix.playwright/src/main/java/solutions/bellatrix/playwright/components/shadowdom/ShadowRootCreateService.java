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

package solutions.bellatrix.playwright.components.shadowdom;

import solutions.bellatrix.core.plugins.EventListener;
import solutions.bellatrix.playwright.components.common.create.RelativeCreateService;
import solutions.bellatrix.playwright.components.common.ComponentActionEventArgs;
import solutions.bellatrix.playwright.components.common.webelement.WebElement;
import solutions.bellatrix.playwright.findstrategies.CssFindStrategy;
import solutions.bellatrix.playwright.findstrategies.FindStrategy;
import solutions.bellatrix.playwright.findstrategies.XpathFindStrategy;

public class ShadowRootCreateService extends RelativeCreateService {
    public ShadowRootCreateService(ShadowRoot baseComponent, EventListener<ComponentActionEventArgs> creating, EventListener<ComponentActionEventArgs> created) {
        super(baseComponent, creating, created);
    }

    @Override
    protected WebElement locate(FindStrategy findStrategy) {
        var host = baseComponent.getWrappedElement();
        if (findStrategy instanceof XpathFindStrategy xpath) {
            return host.locate("shadow=xpath:" + xpath.relativeExpression());
        }
        if (findStrategy instanceof CssFindStrategy css) {
            var selector = css.getValue();
            return host.locate("shadow=css:" + (selector.startsWith("css=") ? selector.substring(4) : selector));
        }
        return findStrategy.convert(host).and(host.locate("shadow=descendants"));
    }
}
