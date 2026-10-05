package ru.ops.console.ui.kafka;

import com.vaadin.flow.server.VaadinSession;

/**
 * Message draft for the publish form (passed between views via the session,
 * as the value may be too large for a URL).
 */
public record ProduceDraft(String topic, Integer partition, String key, String value, String headers) {

    public static void set(ProduceDraft draft) {
        VaadinSession.getCurrent().setAttribute(ProduceDraft.class, draft);
    }

    /** Takes the draft (one-shot). */
    public static ProduceDraft take() {
        VaadinSession s = VaadinSession.getCurrent();
        ProduceDraft d = s.getAttribute(ProduceDraft.class);
        s.setAttribute(ProduceDraft.class, null);
        return d;
    }
}
