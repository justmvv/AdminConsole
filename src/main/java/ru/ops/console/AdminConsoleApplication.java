package ru.ops.console;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Support console for a financial transaction orchestrator (and similar systems).
 * Backend and UI are pure Java (Spring Boot + Vaadin Flow, default Lumo theme).
 * <p>
 * {@link Push} lets long operations (reading Kafka, exports) run in the background
 * and deliver results to the browser without blocking the UI.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Push
public class AdminConsoleApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(AdminConsoleApplication.class, args);
    }
}
