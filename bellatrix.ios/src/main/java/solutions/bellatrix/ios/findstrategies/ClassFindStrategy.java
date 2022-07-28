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

package solutions.bellatrix.ios.findstrategies;

import io.appium.java_client.MobileElement;
import io.appium.java_client.ios.IOSDriver;

import java.util.List;

public class ClassFindStrategy extends FindStrategy {
    public ClassFindStrategy(String value) {
        super(value);
    }

    @Override
    public MobileElement findElement(IOSDriver<MobileElement> driver) {
        return driver.findElementByClassName(getValue());
    }

    @Override
    public List<MobileElement> findAllElements(IOSDriver<MobileElement> driver) {
        return driver.findElementsByClassName(getValue());
    }

    @Override
    public MobileElement findElement(MobileElement element) {
        return element.findElementByClassName(getValue());
    }

    @Override
    public List<MobileElement> findAllElements(MobileElement element) {
        return element.findElementsByClassName(getValue());
    }

    @Override
    public String toString() {
        return String.format("text = %s", getValue());
    }
}