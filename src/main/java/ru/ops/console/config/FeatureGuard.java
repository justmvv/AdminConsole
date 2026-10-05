package ru.ops.console.config;

import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.springframework.stereotype.Component;

/**
 * Blocks views of disabled sections (including direct links): such a view looks like a non-existent one.
 * The menu hides them separately, and services of a disabled section refuse to connect to their system.
 */
@Component
public class FeatureGuard implements VaadinServiceInitListener {

    private final ConsoleProperties.Features features;

    public FeatureGuard(ConsoleProperties props) {
        this.features = props.getFeatures();
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(ui -> ui.getUI().addBeforeEnterListener(enter -> {
            RequiresFeature required = enter.getNavigationTarget().getAnnotation(RequiresFeature.class);
            if (required != null && !features.isEnabled(required.value())) {
                enter.rerouteToError(NotFoundException.class,
                        "Раздел «" + required.value().label() + "» выключен в настройках консоли");
            }
        }));
    }
}
