package ru.ops.console.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditEvent;
import ru.ops.console.audit.AuditService;
import ru.ops.console.kafka.MessageFormat;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.common.Ui;

@Route(value = "audit", layout = MainLayout.class)
@PageTitle("Журнал аудита")
@RolesAllowed(Roles.ADMIN)
public class AuditView extends VerticalLayout {

    public AuditView(AuditService audit) {
        setSizeFull();
        add(new H2("Журнал аудита"));

        if (!audit.isJdbcEnabled()) {
            add(new Paragraph("Запись аудита в БД выключена (console.audit.jdbc-enabled=false) — "
                    + "события пишутся только в файл logs/audit.log."));
            return;
        }

        TextField user = new TextField("Пользователь");
        Select<AuditAction> action = new Select<>();
        action.setLabel("Действие");
        action.setItems(AuditAction.values());
        action.setEmptySelectionAllowed(true);
        action.setEmptySelectionCaption("Все");
        TextField target = new TextField("Объект");

        Grid<AuditEvent> grid = new Grid<>();
        grid.addColumn(e -> Ui.ts(e.ts())).setHeader("Время").setWidth("200px").setFlexGrow(0);
        grid.addColumn(AuditEvent::username).setHeader("Пользователь").setAutoWidth(true).setFlexGrow(0);
        grid.addComponentColumn(e -> Ui.badge(e.action() == null ? "?" : e.action().name(),
                e.success() ? null : "error")).setHeader("Действие").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(AuditEvent::target).setHeader("Объект").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(AuditEvent::reason).setHeader("Обоснование").setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(e -> e.details() == null ? "" : MessageFormat.preview(
                e.details().getBytes(java.nio.charset.StandardCharsets.UTF_8), 200)).setHeader("Детали").setFlexGrow(1);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.addItemClickListener(e -> Ui.showText("Событие #" + e.getItem().id(),
                e.getItem().details() == null ? "" : MessageFormat.prettyJson(e.getItem().details())));

        Runnable load = () -> {
            try {
                grid.setItems(audit.search(user.getValue(), action.getValue(), target.getValue(), 1000));
            } catch (Exception ex) {
                Ui.error(ex);
            }
        };
        Button search = new Button("Найти", VaadinIcon.SEARCH.create(), e -> load.run());
        search.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        search.addClickShortcut(com.vaadin.flow.component.Key.ENTER);

        HorizontalLayout filters = new HorizontalLayout(user, action, target, search);
        filters.setAlignItems(FlexComponent.Alignment.BASELINE);
        add(filters, grid);
        expand(grid);
        load.run();
    }
}
