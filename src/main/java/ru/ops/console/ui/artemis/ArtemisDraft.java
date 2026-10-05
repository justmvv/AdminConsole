package ru.ops.console.ui.artemis;

import com.vaadin.flow.server.VaadinSession;

/** Message draft for the Artemis publish form (passed via the session — the body may be large). */
public record ArtemisDraft(String address, String body, String properties) {

    public static void set(ArtemisDraft draft) {
        VaadinSession.getCurrent().setAttribute(ArtemisDraft.class, draft);
    }

    /** Takes the draft (one-shot). */
    public static ArtemisDraft take() {
        VaadinSession s = VaadinSession.getCurrent();
        ArtemisDraft d = s.getAttribute(ArtemisDraft.class);
        s.setAttribute(ArtemisDraft.class, null);
        return d;
    }
}
