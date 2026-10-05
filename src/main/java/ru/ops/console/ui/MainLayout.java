package ru.ops.console.ui;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;
import ru.ops.console.security.SessionTimeoutService;
import ru.ops.console.ui.artemis.ArtemisProduceView;
import ru.ops.console.ui.artemis.ArtemisQueuesView;
import ru.ops.console.ui.common.IdleSessionGuard;
import ru.ops.console.ui.db.DbView;
import ru.ops.console.ui.kafka.ConsumerGroupsView;
import ru.ops.console.ui.kafka.KafkaProduceView;
import ru.ops.console.ui.kafka.TopicsView;

@PermitAll
public class MainLayout extends AppLayout {

    public MainLayout(ConsoleProperties props, AuthenticationContext authContext,
                      SessionTimeoutService sessionTimeout) {
        setPrimarySection(Section.DRAWER);

        Span title = new Span(props.getUi().getTitle());
        title.getStyle().set("font-size", "var(--lumo-font-size-l)").set("font-weight", "600");

        Span env = new Span(props.getUi().getEnvironmentName());
        env.getStyle()
                .set("background", props.getUi().getEnvironmentColor())
                .set("color", "white")
                .set("padding", "2px 10px")
                .set("border-radius", "8px")
                .set("font-weight", "700")
                .set("letter-spacing", "0.05em");

        Span user = new Span(CurrentUser.name() + "  ·  " + String.join(", ", CurrentUser.roles()));
        user.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");

        Button logout = new Button("Выйти", VaadinIcon.SIGN_OUT.create(), e -> authContext.logout());
        logout.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Div spacer = new Div();
        HorizontalLayout header = new HorizontalLayout(new DrawerToggle(), title, env, spacer, user, logout);
        header.setWidthFull();
        header.expand(spacer);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.getStyle().set("padding-right", "var(--lumo-space-m)");
        addToNavbar(header, new IdleSessionGuard(sessionTimeout, authContext::logout));

        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Обзор", HomeView.class, VaadinIcon.DASHBOARD.create()));

        if (CurrentUser.hasRole(Roles.VIEWER)) {
            if (props.getFeatures().isDb()) {
                nav.addItem(new SideNavItem("Таблицы БД", DbView.class, VaadinIcon.DATABASE.create()));
            }

            if (props.getFeatures().isKafka()) {
                SideNavItem kafka = new SideNavItem("Kafka");
                kafka.setPrefixComponent(VaadinIcon.CLUSTER.create());
                kafka.setExpanded(true);
                kafka.addItem(new SideNavItem("Топики", TopicsView.class, VaadinIcon.LIST.create()));
                kafka.addItem(new SideNavItem("Consumer groups", ConsumerGroupsView.class, VaadinIcon.USERS.create()));
                if (CurrentUser.hasRole(Roles.OPERATOR)) {
                    kafka.addItem(new SideNavItem("Отправить сообщение", KafkaProduceView.class,
                            VaadinIcon.PAPERPLANE.create()));
                }
                nav.addItem(kafka);
            }

            if (props.getFeatures().isArtemis()) {
                SideNavItem artemis = new SideNavItem("Artemis");
                artemis.setPrefixComponent(VaadinIcon.ENVELOPES.create());
                artemis.setExpanded(true);
                artemis.addItem(new SideNavItem("Очереди", ArtemisQueuesView.class, VaadinIcon.LIST.create()));
                if (CurrentUser.hasRole(Roles.OPERATOR)) {
                    artemis.addItem(new SideNavItem("Отправить сообщение", ArtemisProduceView.class,
                            VaadinIcon.PAPERPLANE.create()));
                }
                nav.addItem(artemis);
            }
        }
        if (CurrentUser.hasRole(Roles.ADMIN)) {
            nav.addItem(new SideNavItem("Журнал аудита", AuditView.class, VaadinIcon.RECORDS.create()));
        }

        Div drawer = new Div(nav);
        drawer.getStyle().set("padding", "var(--lumo-space-s)");
        addToDrawer(drawer);
    }
}
