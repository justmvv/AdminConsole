package ru.ops.console.security;

import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.config.ConsoleProperties;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ends sessions after user inactivity.
 * <p>
 * The servlet session timeout does not help here: while a tab is open, Vaadin sends heartbeats
 * and extends the session forever. So activity (clicks, keyboard, scrolling) is tracked
 * explicitly — see {@link ru.ops.console.ui.common.IdleSessionGuard}.
 */
@Service
public class SessionTimeoutService {

    /** HTTP session attribute: the session expired due to inactivity, /session-expired may log out. */
    public static final String EXPIRED_ATTRIBUTE = "console.session-expired";
    /** After how long the server closes the session itself if the browser did not go to /session-expired. */
    private static final long FORCE_INVALIDATE_SECONDS = 10;

    private final ConsoleProperties.Session props;
    private final AuditService audit;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-timeout");
        t.setDaemon(true);
        return t;
    });

    public SessionTimeoutService(ConsoleProperties props, AuditService audit) {
        this.props = props.getSecurity().getSession();
        this.audit = audit;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** The session's tracker (shared by all tabs). Call under the session lock — from the UI thread. */
    public IdleTracker tracker(VaadinSession session) {
        IdleTracker t = session.getAttribute(IdleTracker.class);
        if (t == null) {
            t = new IdleTracker(props.getIdleTimeout(), props.getWarningBefore(), System.currentTimeMillis());
            session.setAttribute(IdleTracker.class, t);
        }
        return t;
    }

    public ScheduledFuture<?> everySecond(Runnable task) {
        return scheduler.scheduleWithFixedDelay(task, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * Marks the session as expired: audit (once per session) and a fallback invalidation of the HTTP session.
     * The browser is then sent to /session-expired, where the regular logout happens.
     */
    public void expire(IdleTracker tracker, String user, WrappedSession http) {
        if (!tracker.markExpired()) return;
        try {
            http.setAttribute(EXPIRED_ATTRIBUTE, Boolean.TRUE);
        } catch (IllegalStateException alreadyInvalidated) {
            return;
        }
        audit.record(user, AuditAction.SESSION_TIMEOUT, null, null, true,
                Map.of("idleTimeout", props.getIdleTimeout().toString()));
        scheduler.schedule(() -> {
            try {
                http.invalidate();
            } catch (IllegalStateException alreadyInvalidated) {
                // the browser has already logged out
            }
        }, FORCE_INVALIDATE_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
