package ru.ops.console.ui.kafka;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.kafka.KafkaAdminService;
import ru.ops.console.kafka.KafkaModel.GroupInfo;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

import java.util.List;
import java.util.Locale;

@RequiresFeature(Feature.KAFKA)
@Route(value = "kafka/groups", layout = MainLayout.class)
@PageTitle("Kafka: consumer groups")
@RolesAllowed(Roles.VIEWER)
public class ConsumerGroupsView extends VerticalLayout {

    private final KafkaAdminService admin;
    private final Grid<GroupInfo> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final Checkbox onlyLag = new Checkbox("Только с lag > 0");
    private final Span status = new Span();
    private List<GroupInfo> all = List.of();

    public ConsumerGroupsView(KafkaAdminService admin) {
        this.admin = admin;
        setSizeFull();

        filter.setPlaceholder("Группа или топик…");
        filter.setPrefixComponent(VaadinIcon.SEARCH.create());
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.LAZY);
        filter.setWidth("320px");
        filter.addValueChangeListener(e -> applyFilter());
        onlyLag.addValueChangeListener(e -> applyFilter());

        Button refresh = new Button("Обновить", VaadinIcon.REFRESH.create(), e -> load());
        HorizontalLayout toolbar = new HorizontalLayout(new H2("Consumer groups"), filter, onlyLag, refresh, status);
        toolbar.setAlignItems(FlexComponent.Alignment.BASELINE);

        grid.addColumn(GroupInfo::groupId).setHeader("Группа").setSortable(true).setComparator(GroupInfo::groupId)
                .setFlexGrow(1);
        grid.addComponentColumn(g -> Ui.badge(g.state(), stateKind(g.state()))).setHeader("Состояние")
                .setAutoWidth(true).setFlexGrow(0);
        grid.addColumn(GroupInfo::members).setHeader("Участников").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END).setSortable(true).setComparator(GroupInfo::members);
        grid.addComponentColumn(g -> {
            Span s = new Span(String.format("%,d", g.totalLag()));
            if (g.totalLag() > 0) s.getStyle().set("color", "var(--lumo-error-text-color)").set("font-weight", "600");
            return s;
        }).setHeader("Lag").setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END)
                .setSortable(true).setComparator(GroupInfo::totalLag);
        grid.addColumn(GroupInfo::partitions).setHeader("Партиций").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(g -> String.join(", ", g.topics())).setHeader("Топики").setFlexGrow(1);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.addItemClickListener(e ->
                UI.getCurrent().navigate(GroupView.class, new RouteParameters("group", e.getItem().groupId())));

        add(toolbar, grid);
        expand(grid);
        load();
    }

    static String stateKind(String state) {
        return switch (state == null ? "" : state.toUpperCase(Locale.ROOT)) {
            case "STABLE" -> "success";
            case "EMPTY" -> "contrast";
            case "DEAD", "UNKNOWN", "?" -> "error";
            default -> null;
        };
    }

    private void load() {
        status.setText("загрузка…");
        Ui.background(this, admin::groups, groups -> {
            all = groups;
            applyFilter();
            status.setText(groups.size() + " групп");
        }, null);
    }

    private void applyFilter() {
        String f = filter.getValue() == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
        grid.setItems(all.stream()
                .filter(g -> !onlyLag.getValue() || g.totalLag() > 0)
                .filter(g -> f.isEmpty() || g.groupId().toLowerCase(Locale.ROOT).contains(f)
                        || g.topics().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(f)))
                .toList());
    }
}
