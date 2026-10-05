package ru.ops.console.ui.kafka;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
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
import ru.ops.console.kafka.KafkaModel.TopicInfo;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

import java.util.List;
import java.util.Locale;

@RequiresFeature(Feature.KAFKA)
@Route(value = "kafka/topics", layout = MainLayout.class)
@PageTitle("Kafka: топики")
@RolesAllowed(Roles.VIEWER)
public class TopicsView extends VerticalLayout {

    private final KafkaAdminService admin;
    private final Grid<TopicInfo> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final Span status = new Span();
    private List<TopicInfo> all = List.of();

    public TopicsView(KafkaAdminService admin) {
        this.admin = admin;
        setSizeFull();

        filter.setPlaceholder("Фильтр по имени…");
        filter.setPrefixComponent(VaadinIcon.SEARCH.create());
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.LAZY);
        filter.setWidth("360px");
        filter.addValueChangeListener(e -> applyFilter());

        Button refresh = new Button("Обновить", VaadinIcon.REFRESH.create(), e -> load());

        HorizontalLayout toolbar = new HorizontalLayout(new H2("Топики"), filter, refresh, status);
        toolbar.setAlignItems(FlexComponent.Alignment.BASELINE);

        grid.addColumn(TopicInfo::name).setHeader("Топик").setSortable(true).setFlexGrow(1)
                .setComparator(TopicInfo::name);
        grid.addColumn(TopicInfo::partitions).setHeader("Партиций").setSortable(true).setAutoWidth(true)
                .setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
        grid.addColumn(TopicInfo::replicationFactor).setHeader("RF").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(t -> String.format("%,d", t.messages())).setHeader("Сообщений (≈)").setSortable(true)
                .setComparator(TopicInfo::messages).setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(t -> t.internal() ? "internal" : "").setHeader("").setAutoWidth(true).setFlexGrow(0);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.addItemClickListener(e ->
                UI.getCurrent().navigate(TopicView.class, new RouteParameters("topic", e.getItem().name())));

        add(toolbar, grid);
        expand(grid);
        load();
    }

    private void load() {
        status.setText("загрузка…");
        Ui.background(this, admin::topics, topics -> {
            all = topics;
            applyFilter();
            status.setText(topics.size() + " топиков");
        }, null);
    }

    private void applyFilter() {
        String f = filter.getValue() == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
        grid.setItems(f.isEmpty() ? all : all.stream().filter(t -> t.name().toLowerCase(Locale.ROOT).contains(f)).toList());
    }
}
