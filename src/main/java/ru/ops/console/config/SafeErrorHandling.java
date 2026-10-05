package ru.ops.console.config;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.ops.console.ui.common.Ui;

/**
 * Replaces Vaadin's default error handler, which logs any unhandled exception with its full message (and thus
 * possibly data from the target database). The user still sees the full message; the log gets {@link SafeLog}.
 */
@Component
public class SafeErrorHandling implements VaadinServiceInitListener {

    private static final Logger log = LoggerFactory.getLogger(SafeErrorHandling.class);

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionInitListener(init -> init.getSession().setErrorHandler(error -> {
            Throwable e = error.getThrowable();
            log.warn("Unhandled UI error: {}", SafeLog.describe(e));
            try {
                Ui.error(e.getMessage() == null ? e.toString() : e.getMessage());
            } catch (Exception ignored) {
                // no UI available (e.g. the session is closing)
            }
        }));
    }
}
