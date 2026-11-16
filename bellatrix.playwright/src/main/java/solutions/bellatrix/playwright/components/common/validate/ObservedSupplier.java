package solutions.bellatrix.playwright.components.common.validate;

import java.util.function.Supplier;

final class ObservedSupplier<T> implements Supplier<T> {
    private final Supplier<T> supplier;
    private boolean observed;
    private T value;
    private RuntimeException failure;

    ObservedSupplier(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    @Override
    public T get() {
        try {
            T current = supplier.get();
            value = current;
            observed = true;
            return current;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }

    T lastObserved() {
        if (observed) {
            return value;
        }
        if (failure != null) {
            throw failure;
        }
        return supplier.get();
    }
}
