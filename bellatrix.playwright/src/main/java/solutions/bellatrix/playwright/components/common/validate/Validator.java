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

package solutions.bellatrix.playwright.components.common.validate;

import lombok.experimental.UtilityClass;
import org.opentest4j.AssertionFailedError;
import solutions.bellatrix.core.configuration.ConfigurationService;
import solutions.bellatrix.core.utilities.SecretMasker;
import solutions.bellatrix.core.utilities.Wait;
import solutions.bellatrix.playwright.components.WebComponent;
import solutions.bellatrix.playwright.configuration.TimeoutSettings;
import solutions.bellatrix.playwright.configuration.WebSettings;
import solutions.bellatrix.playwright.services.WebService;
import solutions.bellatrix.playwright.utilities.functionalinterfaces.Assertable;

import java.util.function.Supplier;

@UtilityClass
public class Validator extends WebService {
    public static final long CONDITION_POLLING_INTERVAL_MILLISECONDS = 100;
    private static final TimeoutSettings timeoutSettings = ConfigurationService.get(WebSettings.class).getTimeoutSettings();

    public static void validateCondition(Wait.Comparator condition) {
        long timeout = timeoutSettings.inMilliseconds().getValidationsTimeout();

        if (!Wait.forConditionUntilTimeout(condition, timeout, CONDITION_POLLING_INTERVAL_MILLISECONDS)) {
            throw new AssertionFailedError(String.format("Assertion failed.\n%s", condition.toString()));
        }
    }

    public static void validateCondition(Wait.Comparator condition, long timeoutInMilliseconds, long pollingIntervalInMilliseconds) {
        if (!Wait.forConditionUntilTimeout(condition, timeoutInMilliseconds, pollingIntervalInMilliseconds)) {
            throw new AssertionFailedError(String.format("Assertion failed.\n%s", condition.toString()));
        }
    }

    public static <T, V> void validate(Assertable assertion, WebComponent component, String attributeName, V value, Supplier<T> supplier, String prefix) {
        try {
            assertion.perform();
        } catch (AssertionError failure) {
            throw new AssertionFailedError(SecretMasker.mask(failureMessage(component, attributeName, value, supplier, prefix)), failure);
        } catch (Exception exception) {
            throw new IllegalArgumentException(String.format("%s\n%s", failureMessage(component, attributeName, value, supplier, prefix), exception));
        }
    }

    private static <T, V> String failureMessage(WebComponent component, String attributeName, V value, Supplier<T> supplier, String prefix) {
        T actual = supplier instanceof ObservedSupplier<T> observed ? observed.lastObserved() : supplier.get();
        return String.format("The %s of %s (%s)%n" +
                        "  Should %s: \"%s\"%n" +
                        "  %" + prefix.length() + "sBut was: \"%s\"%n" +
                        "Test failed on URL: %s",
                attributeName, component.getComponentClass().getSimpleName(), component.getFindStrategy(),
                prefix, value, "", actual.toString().replaceAll("%n", "%n" + String.format("%" + (prefix.length() + 12) + "s", " ")),
                wrappedBrowser().getCurrentPage().url());
    }
}
