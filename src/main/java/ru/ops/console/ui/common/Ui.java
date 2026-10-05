package ru.ops.console.ui.common;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.textfield.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ops.console.config.SafeLog;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small UI helpers: notifications, background tasks, text dialog. */
public final class Ui {

    private static final Logger log = LoggerFactory.getLogger(Ui.class);
    private static final ExecutorService BACKGROUND = Executors.newVirtualThreadPerTaskExecutor();
    public static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private Ui() {
    }

    public static String ts(Instant i) {
        return i == null ? "" : TS.format(i);
    }

    public static void ok(String text) {
        Notification n = Notification.show(text, 4000, Notification.Position.BOTTOM_END);
        n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
    }

    public static void warn(String text) {
        Notification n = Notification.show(text, 6000, Notification.Position.BOTTOM_END);
        n.addThemeVariants(NotificationVariant.LUMO_CONTRAST);
    }

    public static void error(Throwable e) {
        log.warn("Operation failed: {}", SafeLog.describe(e));
        error(e.getMessage() == null ? e.toString() : e.getMessage());
    }

    public static void error(String text) {
        Notification n = new Notification();
        n.addThemeVariants(NotificationVariant.LUMO_ERROR);
        n.setPosition(Notification.Position.MIDDLE);
        n.setDuration(0);
        Button close = new Button("Закрыть", e -> n.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        Span msg = new Span(text);
        msg.getStyle().set("white-space", "pre-wrap").set("max-width", "60vw");
        com.vaadin.flow.component.orderedlayout.HorizontalLayout hl =
                new com.vaadin.flow.component.orderedlayout.HorizontalLayout(msg, close);
        hl.setAlignItems(com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER);
        n.add(hl);
        n.open();
    }

    /**
     * Runs a long operation in the background and delivers the result to the UI via server push.
     * The task and the callbacks run with the caller's SecurityContext.
     */
    public static <T> void background(Component owner, Supplier<T> task, Consumer<T> onSuccess,
                                      Runnable always) {
        UI ui = owner.getUI().orElse(UI.getCurrent());
        // The background thread works on behalf of the same user (audit, pg_stat_activity, role checks)
        SecurityContext context = SecurityContextHolder.getContext();
        CompletableFuture.supplyAsync(() -> withContext(context, task), BACKGROUND)
                .whenComplete((result, err) -> ui.access(() -> withContext(context, () -> {
            try {
                if (err != null) {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    error(cause);
                } else {
                    onSuccess.accept(result);
                }
            } finally {
                if (always != null) always.run();
            }
            return null;
        })));
    }

    private static <T> T withContext(SecurityContext context, Supplier<T> task) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try {
            return task.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    public static void showText(String title, String text) {
        Dialog d = new Dialog();
        d.setHeaderTitle(title);
        TextArea area = new TextArea();
        area.setValue(text == null ? "" : text);
        area.setReadOnly(true);
        area.setWidth("80vw");
        area.setMaxWidth("1100px");
        area.setHeight("60vh");
        area.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        d.add(area);
        Button close = new Button("Закрыть", e -> d.close());
        d.getFooter().add(close);
        d.open();
    }

    /** Colored badge without a CSS theme. kind: success | error | contrast | (otherwise neutral). */
    public static Span badge(String text, String kind) {
        Span s = new Span(text);
        String bg;
        String fg;
        switch (kind == null ? "" : kind) {
            case "success" -> { bg = "var(--lumo-success-color-10pct)"; fg = "var(--lumo-success-text-color)"; }
            case "error" -> { bg = "var(--lumo-error-color-10pct)"; fg = "var(--lumo-error-text-color)"; }
            case "contrast" -> { bg = "var(--lumo-contrast-80pct)"; fg = "var(--lumo-base-color)"; }
            default -> { bg = "var(--lumo-primary-color-10pct)"; fg = "var(--lumo-primary-text-color)"; }
        }
        s.getStyle()
                .set("background", bg).set("color", fg)
                .set("padding", "0 .5em").set("border-radius", "var(--lumo-border-radius-m)")
                .set("font-size", "var(--lumo-font-size-s)").set("font-weight", "500")
                .set("white-space", "nowrap");
        return s;
    }

    public static Span mono(String text) {
        Span s = new Span(text);
        s.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        return s;
    }
}
