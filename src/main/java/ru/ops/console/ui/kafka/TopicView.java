package ru.ops.console.ui.kafka;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.kafka.KafkaAdminService;
import ru.ops.console.kafka.KafkaBrowseService;
import ru.ops.console.kafka.KafkaModel.ConfigEntryInfo;
import ru.ops.console.kafka.KafkaModel.PartitionInfo;
import ru.ops.console.kafka.KafkaModel.TopicDetails;
import ru.ops.console.kafka.KafkaProduceService;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

@RequiresFeature(Feature.KAFKA)
@Route(value = "kafka/topic/:topic", layout = MainLayout.class)
@RolesAllowed(Roles.VIEWER)
public class TopicView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private final KafkaAdminService admin;
    private final KafkaBrowseService browse;
    private final KafkaProduceService produce;
    private String topic = "";

    public TopicView(KafkaAdminService admin, KafkaBrowseService browse, KafkaProduceService produce) {
        this.admin = admin;
        this.browse = browse;
        this.produce = produce;
        setSizeFull();
    }

    @Override
    public String getPageTitle() {
        return "Kafka: " + topic;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        topic = event.getRouteParameters().get("topic").orElse("");
        removeAll();

        Button back = new Button(VaadinIcon.ARROW_LEFT.create(), e -> UI.getCurrent().navigate(TopicsView.class));
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        H2 title = new H2(topic);
        title.getStyle().set("margin", "0");
        HorizontalLayout header = new HorizontalLayout(back, title);
        header.setAlignItems(FlexComponent.Alignment.CENTER);

        if (CurrentUser.hasRole(Roles.OPERATOR) && produce.isTopicAllowed(topic)) {
            Button send = new Button("Отправить сообщение", VaadinIcon.PAPERPLANE.create(), e -> {
                ProduceDraft.set(new ProduceDraft(topic, null, null, null, null));
                UI.getCurrent().navigate(KafkaProduceView.class);
            });
            header.add(send);
        }

        TabSheet tabs = new TabSheet();
        tabs.setSizeFull();

        MessageBrowser browser = new MessageBrowser(topic, browse, produce);
        tabs.add("Сообщения", browser);

        Grid<PartitionInfo> partitions = new Grid<>();
        partitions.addColumn(PartitionInfo::partition).setHeader("Партиция").setAutoWidth(true);
        partitions.addColumn(PartitionInfo::leader).setHeader("Лидер").setAutoWidth(true);
        partitions.addColumn(PartitionInfo::replicas).setHeader("Реплики").setAutoWidth(true);
        partitions.addColumn(p -> p.isr() + (p.underReplicated() ? "  ⚠ under-replicated" : "")).setHeader("ISR")
                .setAutoWidth(true);
        partitions.addColumn(PartitionInfo::beginOffset).setHeader("Начальный офсет").setAutoWidth(true)
                .setTextAlign(ColumnTextAlign.END);
        partitions.addColumn(PartitionInfo::endOffset).setHeader("Конечный офсет").setAutoWidth(true)
                .setTextAlign(ColumnTextAlign.END);
        partitions.addColumn(p -> String.format("%,d", p.messages())).setHeader("Сообщений").setAutoWidth(true)
                .setTextAlign(ColumnTextAlign.END);
        partitions.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        partitions.setSizeFull();
        tabs.add("Партиции", partitions);

        Grid<ConfigEntryInfo> configs = new Grid<>();
        configs.addColumn(ConfigEntryInfo::name).setHeader("Параметр").setAutoWidth(true);
        configs.addColumn(ConfigEntryInfo::value).setHeader("Значение").setFlexGrow(1);
        configs.addColumn(c -> c.isDefault() ? "по умолчанию" : c.source()).setHeader("Источник").setAutoWidth(true);
        configs.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        configs.setSizeFull();
        tabs.add("Конфигурация", configs);

        add(header, tabs);
        expand(tabs);

        Ui.background(this, () -> admin.topic(topic), (TopicDetails d) -> {
            partitions.setItems(d.partitions());
            configs.setItems(d.configs());
            browser.setPartitions(d.partitions().size());
        }, null);
    }
}
