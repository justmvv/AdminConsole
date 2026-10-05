package ru.ops.console.security;

import org.junit.jupiter.api.Test;
import ru.ops.console.security.IdleTracker.Phase;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IdleTrackerTest {

    private static final long MIN = 60_000;

    private final IdleTracker tracker = new IdleTracker(Duration.ofMinutes(15), Duration.ofMinutes(1), 0);

    @Test
    void activeThenWarningThenExpired() {
        assertThat(tracker.state(0).phase()).isEqualTo(Phase.ACTIVE);
        assertThat(tracker.state(14 * MIN - 1).phase()).isEqualTo(Phase.ACTIVE);

        IdleTracker.State warn = tracker.state(14 * MIN + 15_000);
        assertThat(warn.phase()).isEqualTo(Phase.WARNING);
        assertThat(warn.remainingMillis()).isEqualTo(45_000);

        assertThat(tracker.state(15 * MIN).phase()).isEqualTo(Phase.EXPIRED);
    }

    @Test
    void activityRestartsTheCountdown() {
        tracker.touch(14 * MIN + 30_000);
        assertThat(tracker.state(15 * MIN).phase()).isEqualTo(Phase.ACTIVE);
        assertThat(tracker.state(29 * MIN + 30_000).phase()).isEqualTo(Phase.EXPIRED);
    }

    @Test
    void lateActivityReportFromAnotherTabDoesNotMoveClockBack() {
        tracker.touch(10 * MIN);
        tracker.touch(5 * MIN);
        assertThat(tracker.state(23 * MIN).phase()).isEqualTo(Phase.ACTIVE);
    }

    @Test
    void expiredSessionCannotBeRevived() {
        assertThat(tracker.markExpired()).isTrue();
        assertThat(tracker.markExpired()).isFalse();
        tracker.touch(MIN);
        assertThat(tracker.state(MIN).phase()).isEqualTo(Phase.EXPIRED);
    }

    @Test
    void warningLongerThanTimeoutIsClamped() {
        IdleTracker t = new IdleTracker(Duration.ofSeconds(30), Duration.ofMinutes(5), 0);
        assertThat(t.state(0).phase()).isEqualTo(Phase.WARNING);
        assertThat(t.state(0).remainingMillis()).isEqualTo(30_000);
    }
}
