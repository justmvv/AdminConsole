package ru.ops.console.ui.artemis;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
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
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.artemis.ArtemisBrowseService;
import ru.ops.console.artemis.ArtemisModel.BrowseRequest;
import ru.ops.console.artemis.ArtemisModel.BrowseResult;
import ru.ops.console.artemis.ArtemisModel.Message;
import ru.ops.console.artemis.ArtemisModel.StartFrom;
import ru.ops.console.artemis.ArtemisProduceService;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.kafka.MessageFormat;
import ru.ops.console.kafka.MessageFormat.Format;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Browses an Artemis queue without changing its state (browse-only): messages are not consumed.
 */
@RequiresFeature(Feature.ARTEMIS)
@Route(value = "artemis/queue/:queue", layout = MainLayout.class)
@PageTitle("Artemis: очередь")
@RolesAllowed(Roles.VIEWER)
public class ArtemisQueueView extends VerticalLayout implements BeforeEnterObserver {

    /** Property where Artemis keeps the original address of a message in DLQ / ExpiryQueue. */
    private static final String ORIGINAL_ADDRESS = "_AMQ_ORIG_ADDRESS";

    private final ArtemisBrowseService browse;
    private final ArtemisProduceService produce;
    private final H2 title = new H2();
    private final Select<StartFrom> startFrom = new Select<>();
    private final IntegerField limit = new IntegerField("Макс. сообщений");
    private final TextField bodyFilter = new TextField("Тело содержит");
    private final TextField propertyFilter = new TextField("Свойство содержит (имя=значение)");
    private final TextField idFilter = new TextField("ID содержит");
    private final Select<Format> format = new Select<>();
    private final Button load = new Button("Читать", VaadinIcon.PLAY.create());
    private final Span status = new Span();
    private final Grid<Message> grid = new Grid<>();
    private String queue;

    public ArtemisQueueView(ArtemisBrowseService browse, ArtemisProduceService produce) {
        this.browse = browse;
        this.produce = produce;
        setSizeFull();

        startFrom.setLabel("Откуда");
        startFrom.setItems(StartFrom.values());
        startFrom.setItemLabelGenerator(StartFrom::label);
        startFrom.setValue(StartFrom.FIRST);
        startFrom.setWidth("200px");
        limit.setValue(100);
        limit.setMin(1);
        limit.setMax(browse.maxMessages());
        limit.setStepButtonsVisible(true);
        limit.setWidth("150px");
        format.setLabel("Формат");
        format.setItems(Format.values());
        format.setValue(Format.AUTO);
        format.setWidth("120px");
        bodyFilter.setClearButtonVisible(true);
        propertyFilter.setClearButtonVisible(true);
        propertyFilter.setWidth("280px");
        idFilter.setClearButtonVisible(true);

        load.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        load.addClickListener(e -> read());
        load.addClickShortcut(com.vaadin.flow.component.Key.ENTER).listenOn(bodyFilter, propertyFilter, idFilter);

        Paragraph hint = new Paragraph("Просмотр не забирает сообщения из очереди. Очередь читается с головы, "
                + "не более " + browse.maxScan() + " сообщений за раз.");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)")
                .set("margin", "0");

        HorizontalLayout row1 = new HorizontalLayout(startFrom, limit, format, bodyFilter, propertyFilter, idFilter,
                load, status);
        row1.setAlignItems(FlexComponent.Alignment.BASELINE);
        row1.getStyle().set("flex-wrap", "wrap");

        grid.addColumn(m -> Ui.ts(m.timestamp())).setHeader("Время").setWidth("200px").setFlexGrow(0)
                .setSortable(true).setComparator(Message::timestamp);
        grid.addColumn(Message::messageId).setHeader("ID в брокере").setWidth("130px").setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(Message::bodyType).setHeader("Тип").setWidth("90px").setFlexGrow(0);
        grid.addColumn(m -> m.body() == null ? (m.bodyNote() == null ? "" : "⚠ " + m.bodyNote())
                        : MessageFormat.preview(m.body(), 300))
                .setHeader("Тело").setFlexGrow(1).setResizable(true);
        grid.addColumn(m -> m.properties().entrySet().stream()
                        .filter(e -> !e.getKey().startsWith("__AMQ"))
                        .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")))
                .setHeader("Свойства").setWidth("320px").setFlexGrow(0).setResizable(true);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.addItemClickListener(e -> openDetails(e.getItem()));

        add(title, hint, row1, grid);
        expand(grid);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        queue = event.getRouteParameters().get("queue").orElse("");
        title.setText("Очередь " + queue);
        grid.setItems();
        status.setText("");
    }

    private void read() {
        BrowseRequest req = new BrowseRequest(queue, startFrom.getValue(),
                limit.getValue() == null ? 100 : limit.getValue(),
                bodyFilter.getValue(), propertyFilter.getValue(), idFilter.getValue());
        String user = CurrentUser.name();
        load.setEnabled(false);
        status.setText("читаю…");
        Ui.background(this, () -> browse.browse(user, req), (BrowseResult r) -> {
            grid.setItems(r.messages());
            String s = "найдено " + r.messages().size() + ", просмотрено " + r.scanned() + ", " + r.elapsedMs() + " мс";
            if (r.timedOut()) s += " · остановлено по таймауту";
            if (r.truncated()) s += " · в очереди есть ещё сообщения";
            status.setText(s);
        }, () -> {
            load.setEnabled(true);
            if ("читаю…".equals(status.getText())) status.setText("");
        });
    }

    private void openDetails(Message m) {
        Dialog d = new Dialog();
        d.setHeaderTitle(queue + " · сообщение " + m.messageId());
        d.setWidth("min(1100px, 96vw)");
        d.setResizable(true);
        d.setDraggable(true);

        TextArea meta = new TextArea("Заголовки");
        meta.setValue("JMSMessageID = " + (m.userId() == null ? "—" : m.userId())
                + "\nВремя = " + Ui.ts(m.timestamp())
                + "\nАдрес = " + m.address()
                + "\nПриоритет = " + m.priority() + ", durable = " + m.durable()
                + (m.expiration() == null ? "" : "\nИстекает = " + Ui.ts(m.expiration()))
                + "\nТип тела = " + m.bodyType() + ", размер = " + m.bodySize() + " байт");
        meta.setReadOnly(true);
        meta.setWidthFull();

        TextArea props = new TextArea("Свойства");
        props.setValue(m.properties().entrySet().stream().map(e -> e.getKey() + " = " + e.getValue())
                .collect(Collectors.joining("\n")));
        props.setReadOnly(true);
        props.setWidthFull();
        props.setVisible(!m.properties().isEmpty());

        TextArea body = new TextArea("Тело");
        body.setReadOnly(true);
        body.setWidthFull();
        body.setHeight("40vh");
        body.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        Select<Format> fmt = new Select<>();
        fmt.setItems(Format.values());
        fmt.setValue(format.getValue());
        Runnable render = () -> body.setValue(m.body() == null ? (m.bodyNote() == null ? "" : m.bodyNote())
                : MessageFormat.format(m.body(), fmt.getValue()));
        fmt.addValueChangeListener(e -> render.run());
        render.run();

        VerticalLayout content = new VerticalLayout(meta, props, body);
        content.setPadding(false);
        d.add(content);
        d.getFooter().add(new Span("Формат:"), fmt);

        boolean textBody = m.body() != null && ("TEXT".equals(m.bodyType()) || MessageFormat.isValidUtf8(m.body()));
        if (CurrentUser.hasRole(Roles.OPERATOR) && produce.isEnabled() && textBody) {
            Button resend = new Button("Переотправить…", VaadinIcon.PAPERPLANE.create(), e -> {
                // from DLQ / ExpiryQueue — back to the original address
                String target = m.properties().getOrDefault(ORIGINAL_ADDRESS, m.address());
                ArtemisDraft.set(new ArtemisDraft(produce.isAddressAllowed(target) ? target : null,
                        new String(m.body(), StandardCharsets.UTF_8),
                        m.properties().entrySet().stream()
                                .filter(p -> !p.getKey().startsWith("_AMQ") && !p.getKey().startsWith("__AMQ")
                                        && !p.getKey().equals(ArtemisProduceService.USER_PROPERTY))
                                .map(p -> p.getKey() + "=" + p.getValue())
                                .collect(Collectors.joining("\n"))));
                d.close();
                UI.getCurrent().navigate(ArtemisProduceView.class);
            });
            resend.setTooltipText("Открыть форму отправки с копией этого сообщения (из DLQ — на исходный адрес)");
            d.getFooter().add(resend);
        }
        d.getFooter().add(new Button("Закрыть", e -> d.close()));
        d.open();
    }
}
