package solutions.bellatrix.core.plugins;

import lombok.SneakyThrows;

public final class FailureCollector {
    private Throwable failure;

    public FailureCollector() {
    }

    public FailureCollector(Throwable failure) {
        this.failure = failure;
    }

    public void run(Action action) {
        try {
            action.run();
        } catch (Throwable next) {
            if (next instanceof VirtualMachineError) {
                throw (VirtualMachineError) next;
            }
            if (next instanceof ThreadDeath) {
                throw (ThreadDeath) next;
            }
            addFailure(next, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        }
    }

    public Throwable getFailure() {
        return failure;
    }

    @SneakyThrows
    public void throwIfAny() {
        if (failure != null) {
            throw failure;
        }
    }

    public static Exception asException(Throwable failure) {
        return failure instanceof Exception ? (Exception) failure : new Exception(failure);
    }

    private void addFailure(Throwable next, java.util.Set<Throwable> visited) {
        if (next == null || !visited.add(next)) {
            return;
        }
        if (failure == null) {
            failure = next;
        } else if (!contains(failure, next)) {
            if (contains(next, failure)) {
                addFailure(next.getCause(), visited);
                for (Throwable suppressed : next.getSuppressed()) {
                    addFailure(suppressed, visited);
                }
            } else {
                failure.addSuppressed(next);
            }
        }
    }

    private static boolean contains(Throwable root, Throwable target) {
        return contains(root, target, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static boolean contains(Throwable root, Throwable target, java.util.Set<Throwable> visited) {
        if (root == target) {
            return true;
        }
        if (root == null || !visited.add(root)) {
            return false;
        }
        if (contains(root.getCause(), target, visited)) {
            return true;
        }
        for (Throwable suppressed : root.getSuppressed()) {
            if (contains(suppressed, target, visited)) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    public interface Action {
        void run() throws Throwable;
    }
}
