package ru.ops.console.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks user inactivity within one session (shared by all tabs).
 * Time is passed explicitly — the logic does not depend on the clock and is unit-tested.
 */
public final class IdleTracker {

    public enum Phase { ACTIVE, WARNING, EXPIRED }

    /** @param remainingMillis time left until the session ends */
    public record State(Phase phase, long remainingMillis) {
    }

    private final long timeoutMillis;
    private final long warningMillis;
    private final AtomicLong lastActivity;
    private final AtomicBoolean expired = new AtomicBoolean();

    public IdleTracker(Duration timeout, Duration warningBefore, long now) {
        this.timeoutMillis = timeout.toMillis();
        this.warningMillis = Math.min(Math.max(0, warningBefore.toMillis()), timeoutMillis);
        this.lastActivity = new AtomicLong(now);
    }

    /** User activity. Extends nothing once the session has expired. */
    public void touch(long now) {
        if (!expired.get()) lastActivity.accumulateAndGet(now, Math::max);
    }

    public State state(long now) {
        if (expired.get()) return new State(Phase.EXPIRED, 0);
        long remaining = timeoutMillis - (now - lastActivity.get());
        if (remaining <= 0) return new State(Phase.EXPIRED, 0);
        if (remaining <= warningMillis) return new State(Phase.WARNING, remaining);
        return new State(Phase.ACTIVE, remaining);
    }

    /** @return true only for the first call (the audit record is written once per session). */
    public boolean markExpired() {
        return expired.compareAndSet(false, true);
    }
}
