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

package solutions.bellatrix.core.utilities;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

// Based on http://neutrofoton.github.io/blog/2013/08/29/generic-singleton-pattern-in-java/
// Can be used inside App design pattern.
@SuppressWarnings("unchecked")
@UtilityClass
public class SingletonFactory extends ObjectFactory {
    private static final ThreadLocal<Map<Class<?>, Object>> mapHolder = ThreadLocal.withInitial(HashMap::new);

    public static <T> T getInstance(Class<T> classOf, Object... initargs) {
        if (!mapHolder.get().containsKey(classOf)) {
            T obj = tryGetInstance(classOf, initargs);
            register(obj);
        }

        return (T)mapHolder.get().get(classOf);
    }

    private static <T> T tryGetInstance(Class<T> classOf, Object... initargs) {
        try {
            return newInstance(classOf, initargs);
        } catch (InvocationTargetException e) {
            Log.error(e.getTargetException().getMessage(), e);
            return null;
        } catch (InstantiationException | IllegalAccessException |
                 ConstructorNotFoundException e) {
            Log.error("Failed to create instance of the class %s.\nException was:\n%s".formatted(classOf.getName(), e.getMessage()));
            return null;
        }
    }

    @SneakyThrows
    public static <T> T getRequiredInstance(Class<T> classOf, Object... initargs) {
        try {
            return Objects.requireNonNull(getOrConstruct(classOf, initargs),
                    () -> "No registered instance of " + classOf.getName());
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } catch (ReflectiveOperationException | IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new IllegalStateException("Failed to construct " + classOf.getName(), failure);
        }
    }

    private static <T> T getOrConstruct(Class<T> classOf, Object[] initargs) throws ReflectiveOperationException {
        var instances = mapHolder.get();
        if (!instances.containsKey(classOf)) {
            T instance = (T) classOf.getConstructors()[0].newInstance(initargs);
            register(instance);
        }
        return (T) instances.get(classOf);
    }

    public static <T> void register(T instance) {
        if (instance != null)
            mapHolder.get().put(instance.getClass(), instance);
    }

    public static <T> void register(Class<?> classKey, T instance) {
        if (instance != null)
            mapHolder.get().put(classKey, instance);
    }

    public static boolean containsKey(Class<?> classOf) {
        return mapHolder.get().containsKey(classOf);
    }

    public static boolean containsValue(Object object) {
        return mapHolder.get().containsValue(object);
    }

    public static void clear() {
        mapHolder.get().clear();
    }
}