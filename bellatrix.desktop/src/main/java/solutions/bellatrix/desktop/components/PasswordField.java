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

package solutions.bellatrix.desktop.components;

import solutions.bellatrix.core.plugins.EventListener;
import solutions.bellatrix.desktop.components.contracts.ComponentDisabled;

public class PasswordField extends DesktopComponent implements ComponentDisabled {
    public final static EventListener<ComponentActionEventArgs> SETTING_PASSWORD = new EventListener<>();
    public final static EventListener<ComponentActionEventArgs> PASSWORD_SET = new EventListener<>();

    @Override
    public Class<?> getComponentClass() {
        return getClass();
    }

    public void setPassword(String password) {
        defaultSetText(SETTING_PASSWORD, PASSWORD_SET, password);
    }

    public String getPassword() {
        return defaultGetText();
    }

    @Override
    public boolean isDisabled() {
        return false;
    }
}
