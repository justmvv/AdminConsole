package ru.ops.console.ui.artemis;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
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
import ru.ops.console.artemis.ArtemisModel.QueueInfo;
import ru.ops.console.artemis.ArtemisQueueService;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

import java.util.List;
import java.util.Locale;

@RequiresFeature(Feature.ARTEMIS)
@Route(value = "artemis/queues", layout = MainLayout.class)
@PageTitle("Artemis: очереди")
@RolesAllowed(Roles.VIEWER)
public class ArtemisQueuesView extends VerticalLayout {

    private final ArtemisQueueService queues;
    private final Grid<QueueInfo> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final Span status = new Span();
    private final Paragraph note = new Paragraph();
    private List<QueueInfo> all = List.of();

    public ArtemisQueuesView(ArtemisQueueService queues) {
        this.queues = queues;
        setSizeFull();

        filter.setPlaceholder("Фильтр по имени…");
        filter.setPrefixComponent(VaadinIcon.SEARCH.create());
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.LAZY);
        filter.setWidth("360px");
        filter.addValueChangeListener(e -> applyFilter());

        Button refresh = new Button("Обновить", VaadinIcon.REFRESH.create(), e -> load());
        HorizontalLayout toolbar = new HorizontalLayout(new H2("Очереди Artemis"), filter, refresh, status);
        toolbar.setAlignItems(FlexComponent.Alignment.BASELINE);

        note.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)")
                .set("margin", "0");
        note.setVisible(false);

        grid.addColumn(QueueInfo::name).setHeader("Очередь").setSortable(true).setFlexGrow(1)
                .setComparator(QueueInfo::name);
        grid.addColumn(q -> q.address() == null ? "" : q.address()).setHeader("Адрес").setFlexGrow(1);
        grid.addColumn(q -> q.routingType() == null ? "" : q.routingType()).setHeader("Тип").setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(q -> q.messageCount() == null ? "?" : String.format("%,d", q.messageCount()))
                .setHeader("Сообщений").setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END)
                .setSortable(true).setComparator(q -> q.messageCount() == null ? -1 : q.messageCount());
        grid.addColumn(q -> q.consumerCount() == null ? "?" : String.valueOf(q.consumerCount()))
                .setHeader("Потребителей").setAutoWidth(true).setFlexGrow(0).setTextAlign(ColumnTextAlign.END);
        grid.addColumn(q -> q.fromConfig() ? "из настроек" : "").setHeader("").setAutoWidth(true).setFlexGrow(0);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.addItemClickListener(e ->
                UI.getCurrent().navigate(ArtemisQueueView.class, new RouteParameters("queue", e.getItem().name())));

        add(toolbar, note, grid);
        expand(grid);
        load();
    }

    private void load() {
        status.setText("загрузка…");
        Ui.background(this, queues::queues, list -> {
            all = list.queues();
            note.setText(list.managementNote() == null ? "" : list.managementNote());
            note.setVisible(list.managementNote() != null);
            applyFilter();
            status.setText(all.size() + " очередей");
        }, null);
    }

    private void applyFilter() {
        String f = filter.getValue() == null ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);
        grid.setItems(f.isEmpty() ? all : all.stream()
                .filter(q -> q.name().toLowerCase(Locale.ROOT).contains(f)
                        || (q.address() != null && q.address().toLowerCase(Locale.ROOT).contains(f)))
                .toList());
    }
}
