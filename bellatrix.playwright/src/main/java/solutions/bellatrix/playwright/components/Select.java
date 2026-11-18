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

package solutions.bellatrix.playwright.components;

import solutions.bellatrix.core.plugins.EventListener;
import solutions.bellatrix.playwright.components.common.ComponentActionEventArgs;
import solutions.bellatrix.playwright.components.contracts.ComponentDisabled;
import solutions.bellatrix.playwright.components.contracts.ComponentReadonly;
import solutions.bellatrix.playwright.components.contracts.ComponentRequired;

import java.util.List;


public class Select extends WebComponent implements ComponentDisabled, ComponentRequired, ComponentReadonly {
    public final static EventListener<ComponentActionEventArgs> SELECTING = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> SELECTED = new EventListener<>();

    public Option getSelected() {
        return create().byCss(Option.class, "option:checked");
    }

    public List<Option> getAllOptions() {
        return create().allByTag(Option.class, "option");
    }

    public void selectByText(String text) {
        defaultSelectByText(SELECTING, SELECTED, text);
    }

    public void selectByValue(String value) {
        defaultSelectByValue(SELECTING, SELECTED, value);
    }

    public void selectByIndex(int index) {
        defaultSelectByIndex(SELECTING, SELECTED, index);
    }

    @Override
    public boolean isDisabled() {
        return defaultGetDisabledAttribute();
    }

    @Override
    public boolean isReadonly() {
        return defaultGetReadonlyAttribute();
    }

    @Override
    public boolean isRequired() {
        return defaultGetRequiredAttribute();
    }
}
