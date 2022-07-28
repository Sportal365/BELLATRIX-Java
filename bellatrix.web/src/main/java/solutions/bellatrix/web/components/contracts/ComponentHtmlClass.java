/*
 * Copyright 2022 Automate The Planet Ltd.
 * Author: Teodor Nikolov
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.bellatrix.web.components.contracts;

import lombok.SneakyThrows;
import solutions.bellatrix.core.utilities.SingletonFactory;
import solutions.bellatrix.web.components.WebComponent;
import solutions.bellatrix.web.validations.ComponentValidator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

public interface ComponentHtmlClass extends Component {
    String getHtmlClass();

    @SneakyThrows
    default void validateClassIs(String value) {
        try {
            Method method = ComponentValidator.class.getDeclaredMethod("defaultValidateAttributeIs", WebComponent.class, Supplier.class, String.class, String.class);
            method.invoke(SingletonFactory.getInstance(ComponentValidator.class), (WebComponent)this, (Supplier<String>)this::getHtmlClass, value, "class");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SneakyThrows
    default void validateClassIsSet() {
        try {
            Method method = ComponentValidator.class.getDeclaredMethod("defaultValidateAttributeIsSet", WebComponent.class, Supplier.class, String.class);
            method.invoke(SingletonFactory.getInstance(ComponentValidator.class), (WebComponent)this, (Supplier<String>)this::getHtmlClass, "class");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SneakyThrows
    default void validateClassNotSet() {
        try {
            Method method = ComponentValidator.class.getDeclaredMethod("defaultValidateAttributeNotSet", WebComponent.class, Supplier.class, String.class);
            method.invoke(SingletonFactory.getInstance(ComponentValidator.class), (WebComponent)this, (Supplier<String>)this::getHtmlClass, "class");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SneakyThrows
    default void validateClassContains(String value) {
        try {
            Method method = ComponentValidator.class.getDeclaredMethod("defaultValidateAttributeContains", WebComponent.class, Supplier.class, String.class, String.class);
            method.invoke(SingletonFactory.getInstance(ComponentValidator.class), (WebComponent)this, (Supplier<String>)this::getHtmlClass, value, "class");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SneakyThrows
    default void validateClassNotContains(String value) {
        try {
            Method method = ComponentValidator.class.getDeclaredMethod("defaultValidateAttributeNotContains", WebComponent.class, Supplier.class, String.class, String.class);
            method.invoke(SingletonFactory.getInstance(ComponentValidator.class), (WebComponent)this, (Supplier<String>)this::getHtmlClass, value, "class");
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
