package ru.ops.console.config;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Caps how many heavy operations of one kind (message browsing, CSV export) run at the same time across
 * all users, protecting the console's memory and the target systems. Extra requests wait a little and
 * then get a clear "try again" error instead of piling up.
 */
public final class ConcurrencyLimit {

    /** All permits are busy — the console is serving other users' heavy requests. */
    public static class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }

    private final Semaphore permits;
    private final String operation;
    private final long waitMillis;

    public ConcurrencyLimit(String operation, int maxParallel, long waitMillis) {
        this.permits = new Semaphore(Math.max(1, maxParallel), true);
        this.operation = operation;
        this.waitMillis = waitMillis;
    }

    public <T> T run(Supplier<T> task) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusyException("Операция прервана");
        }
        if (!acquired) {
            throw new BusyException("Сейчас выполняется слишком много операций «" + operation
                    + "» других пользователей. Повторите через несколько секунд.");
        }
        try {
            return task.get();
        } finally {
            permits.release();
        }
    }
}
