/*
 * Copyright 2024 Automate The Planet Ltd.
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

import java.time.Duration;
import java.util.Arrays;

public class Wait {
    public static void retry(Runnable action, int timesToRetry, long sleepInterval, Class<? extends Throwable> ... exceptionsToIgnore) {
        Wait.retry(action, timesToRetry, sleepInterval, true, exceptionsToIgnore);
    }

    public static void retry(Runnable action, int timesToRetry, long sleepInterval, boolean shouldThrowException, Class<? extends Throwable> ... exceptionsToIgnore) {
        retry(action, timesToRetry, sleepInterval, shouldThrowException, shouldThrowException, exceptionsToIgnore);
    }

    public static boolean tryRetry(Runnable action, int timesToRetry, long sleepInterval, Class<? extends Throwable> ... exceptionsToIgnore) {
        return retry(action, timesToRetry, sleepInterval, false, true, exceptionsToIgnore);
    }

    private static boolean retry(Runnable action, int timesToRetry, long sleepInterval,
                                 boolean throwOnExhaustion, boolean throwOnUnexpected,
                                 Class<? extends Throwable> ... exceptionsToIgnore) {
        if (timesToRetry < 0 || sleepInterval < 0) {
            throw new IllegalArgumentException("Retry count and sleep interval must not be negative");
        }
        long sleepMilliseconds = Duration.ofSeconds(sleepInterval).toMillis();
        for (int attempt = 0; ; attempt++) {
            try {
                action.run();
                return true;
            } catch (Exception exception) {
                if (!isIgnored(exception, exceptionsToIgnore)) {
                    return finishRetry(exception, throwOnUnexpected);
                }
                if (attempt == timesToRetry) {
                    return finishRetry(exception, throwOnExhaustion);
                }
            }
            if (sleepMilliseconds > 0) {
                Wait.forMilliseconds(sleepMilliseconds);
            }
        }
    }

    public static void retry(Runnable action, Class<? extends Throwable> ... exceptionsToIgnore) {
        retry(action, Duration.ofSeconds(30), Duration.ofSeconds(1), true, exceptionsToIgnore);
    }

    public static boolean retry(Runnable action, Duration timeout, Duration sleepInterval, Boolean shouldThrowException, Class<? extends Throwable> ... exceptionsToIgnore) {
        if (timeout.isNegative() || timeout.isZero() || sleepInterval.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive and sleep interval must not be negative");
        }
        long timeoutNanos = timeout.toNanos();
        long start = System.nanoTime();
        Exception lastException;
        do {
            try {
                action.run();
                return true;
            } catch (Exception exception) {
                lastException = exception;
                if (!isIgnored(exception, exceptionsToIgnore)) {
                    return finishRetry(exception, shouldThrowException);
                }
            }
            long remainingNanos = timeoutNanos - (System.nanoTime() - start);
            if (remainingNanos <= 0) {
                break;
            }
            long sleepMilliseconds = Math.min(sleepInterval.toMillis(), remainingNanos / 1_000_000);
            if (sleepMilliseconds > 0) {
                Wait.forMilliseconds(sleepMilliseconds);
            }
        } while (System.nanoTime() - start < timeoutNanos);

        return finishRetry(lastException, shouldThrowException);
    }

    public static void retry(Runnable action, Duration sleepInterval, Duration timeout, Class<? extends Throwable> ... exceptionsToIgnore) {
        Wait.retry(action, timeout, sleepInterval, true, exceptionsToIgnore);
    }

    private static boolean isIgnored(Exception exception, Class<? extends Throwable>[] exceptionsToIgnore) {
        return Arrays.stream(exceptionsToIgnore).anyMatch(type -> type.isInstance(exception));
    }

    private static boolean finishRetry(Exception exception, boolean shouldThrowException) {
        if (shouldThrowException) {
            throw new RuntimeException(exception);
        }
        return false;
    }

    public static void forMilliseconds(long millis) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Wait interrupted");
            }
            Log.info("Waiting for %s milliseconds".formatted(millis));
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public static boolean forConditionUntilTimeout(Comparator condition, long timeoutInMilliseconds, long pollingIntervalInMilliseconds) {
        long startTime = System.currentTimeMillis();

        while ((System.currentTimeMillis() - startTime) <= (timeoutInMilliseconds)) {
            try {
                if (condition.evaluate()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(pollingIntervalInMilliseconds);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interrupted);
            }
        }

        return false;
    }

    @FunctionalInterface
    public interface Comparator {
        boolean evaluate();
    }
}
