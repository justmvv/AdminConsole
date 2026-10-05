package ru.ops.console.ui.common;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.server.WrappedSession;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.IdleTracker;
import ru.ops.console.security.SessionExpiredController;
import ru.ops.console.security.SessionTimeoutService;

import java.util.concurrent.ScheduledFuture;

/**
 * Invisible component in {@link ru.ops.console.ui.MainLayout}: reports user activity to the server,
 * shows a countdown warning {@code warning-before} the end and ends the session.
 */
public class IdleSessionGuard extends Div {

    /** At most one activity report per 15 s — no need for extra requests. */
    private static final int ACTIVITY_THROTTLE_MS = 15_000;

    private static final String LISTEN_JS = """
            const el = this, throttle = $0;
            let last = 0;
            const onActivity = () => {
              const now = Date.now();
              if (el.isConnected && now - last >= throttle) {
                last = now;
                el.$server.activity();
              }
            };
            for (const type of ['pointerdown', 'keydown', 'wheel', 'touchstart']) {
              document.addEventListener(type, onActivity, {capture: true, passive: true});
            }""";

    private final SessionTimeoutService service;
    private final Runnable logout;

    private IdleTracker tracker;
    private String user;
    private WrappedSession httpSession;
    private ScheduledFuture<?> ticks;
    private Dialog warning;
    private Span countdown;

    public IdleSessionGuard(SessionTimeoutService service, Runnable logout) {
        this.service = service;
        this.logout = logout;
        getStyle().set("display", "none");
    }

    @Override
    protected void onAttach(AttachEvent event) {
        if (!service.isEnabled()) return;
        UI ui = event.getUI();
        user = CurrentUser.name();
        httpSession = ui.getSession().getSession();
        tracker = service.tracker(ui.getSession());
        tracker.touch(System.currentTimeMillis());
        getElement().executeJs(LISTEN_JS, ACTIVITY_THROTTLE_MS);
        ticks = service.everySecond(() -> {
            try {
                ui.access(() -> tick(ui));
            } catch (UIDetachedException e) {
                stop();
            }
        });
    }

    @Override
    protected void onDetach(DetachEvent event) {
        stop();
    }

    @ClientCallable
    private void activity() {
        if (tracker != null) tracker.touch(System.currentTimeMillis());
    }

    private void tick(UI ui) {
        IdleTracker.State state = tracker.state(System.currentTimeMillis());
        switch (state.phase()) {
            case ACTIVE -> closeWarning();
            case WARNING -> showWarning(state.remainingMillis());
            case EXPIRED -> {
                stop();
                closeWarning();
                service.expire(tracker, user, httpSession);
                ui.getPage().setLocation(SessionExpiredController.PATH);
            }
        }
    }

    private void showWarning(long remainingMillis) {
        if (warning == null) {
            countdown = new Span();
            warning = new Dialog();
            warning.setHeaderTitle("Сеанс скоро будет завершён");
            warning.setCloseOnEsc(false);
            warning.setCloseOnOutsideClick(false);
            warning.add(countdown);
            Button stay = new Button("Продолжить работу", e -> {
                activity();
                closeWarning();
            });
            stay.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            Button exit = new Button("Выйти", e -> logout.run());
            exit.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            warning.getFooter().add(exit, stay);
            warning.open();
            stay.focus();
        }
        long seconds = (remainingMillis + 999) / 1000;
        countdown.setText("Из-за отсутствия действий сеанс будет завершён через %d:%02d."
                .formatted(seconds / 60, seconds % 60));
    }

    private void closeWarning() {
        if (warning != null) {
            warning.close();
            warning = null;
            countdown = null;
        }
    }

    private void stop() {
        if (ticks != null) {
            ticks.cancel(false);
            ticks = null;
        }
    }
}
