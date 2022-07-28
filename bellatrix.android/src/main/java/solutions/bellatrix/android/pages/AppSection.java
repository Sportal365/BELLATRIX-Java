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

package solutions.bellatrix.android.pages;

import solutions.bellatrix.android.services.AppService;
import solutions.bellatrix.android.services.ComponentCreateService;

import java.lang.reflect.ParameterizedType;

public abstract class AppSection<MapT extends PageMap, AssertionsT extends PageAsserts<MapT>> {
    public AppService browser() {
        return new AppService();
    }

    public ComponentCreateService create() {
        return new ComponentCreateService();
    }

    public MapT map() {
        try {
            var elementsClass = (Class<MapT>)((ParameterizedType)getClass().getGenericSuperclass()).getActualTypeArguments()[0];
            return elementsClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    public AssertionsT asserts() {
        try {
            var assertionsClass = (Class<AssertionsT>)((ParameterizedType)getClass().getGenericSuperclass()).getActualTypeArguments()[1];
            return assertionsClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }
}
