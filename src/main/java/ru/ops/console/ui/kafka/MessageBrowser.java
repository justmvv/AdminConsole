package ru.ops.console.ui.kafka;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import ru.ops.console.kafka.KafkaBrowseService;
import ru.ops.console.kafka.KafkaModel.BrowseRequest;
import ru.ops.console.kafka.KafkaModel.BrowseResult;
import ru.ops.console.kafka.KafkaModel.Header;
import ru.ops.console.kafka.KafkaModel.Message;
import ru.ops.console.kafka.KafkaModel.StartFrom;
import ru.ops.console.kafka.KafkaProduceService;
import ru.ops.console.kafka.MessageFormat;
import ru.ops.console.kafka.MessageFormat.Format;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.common.Ui;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Topic message browser: start mode, partition, key/value/header filters,
 * display format. Reading runs in the background.
 */
public class MessageBrowser extends VerticalLayout {

    private static final String ALL = "Все";

    private final String topic;
    private final KafkaBrowseService browse;
    private final KafkaProduceService produce;

    private final Select<String> partition = new Select<>();
    private final Select<StartFrom> startFrom = new Select<>();
    private final TextField offset = new TextField("Офсет");
    private final DateTimePicker timestamp = new DateTimePicker("Время");
    private final IntegerField limit = new IntegerField("Макс. сообщений");
    private final TextField keyFilter = new TextField("Ключ содержит");
    private final TextField valueFilter = new TextField("Значение содержит");
    private final TextField headerFilter = new TextField("Заголовок содержит");
    private final Select<Format> format = new Select<>();
    private final Button load = new Button("Читать", VaadinIcon.PLAY.create());
    private final Span status = new Span();
    private final Grid<Message> grid = new Grid<>();

    public MessageBrowser(String topic, KafkaBrowseService browse, KafkaProduceService produce) {
        this.topic = topic;
        this.browse = browse;
        this.produce = produce;
        setSizeFull();
        setPadding(false);

        partition.setLabel("Партиция");
        partition.setItems(ALL);
        partition.setValue(ALL);
        partition.setWidth("110px");

        startFrom.setLabel("Откуда");
        startFrom.setItems(StartFrom.values());
        startFrom.setItemLabelGenerator(StartFrom::label);
        startFrom.setValue(StartFrom.LATEST);
        startFrom.setWidth("190px");
        startFrom.addValueChangeListener(e -> updateVisibility());

        offset.setWidth("140px");
        timestamp.setStep(java.time.Duration.ofMinutes(1));
        limit.setValue(100);
        limit.setMin(1);
        limit.setMax(browse.maxMessages());
        limit.setStepButtonsVisible(true);
        limit.setWidth("150px");

        keyFilter.setClearButtonVisible(true);
        valueFilter.setClearButtonVisible(true);
        headerFilter.setClearButtonVisible(true);

        format.setLabel("Формат");
        format.setItems(Format.values());
        format.setValue(Format.AUTO);
        format.setWidth("120px");

        load.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        load.addClickListener(e -> read());
        load.addClickShortcut(com.vaadin.flow.component.Key.ENTER).listenOn(keyFilter, valueFilter, headerFilter, offset);

        HorizontalLayout row1 = new HorizontalLayout(partition, startFrom, offset, timestamp, limit, format);
        row1.setAlignItems(FlexComponent.Alignment.BASELINE);
        row1.getStyle().set("flex-wrap", "wrap");
        HorizontalLayout row2 = new HorizontalLayout(keyFilter, valueFilter, headerFilter, load, status);
        row2.setAlignItems(FlexComponent.Alignment.BASELINE);
        row2.getStyle().set("flex-wrap", "wrap");

        grid.addColumn(Message::partition).setHeader("P").setWidth("60px").setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END).setSortable(true);
        grid.addColumn(Message::offset).setHeader("Офсет").setWidth("110px").setFlexGrow(0)
                .setTextAlign(ColumnTextAlign.END).setSortable(true);
        grid.addColumn(m -> Ui.ts(m.timestamp())).setHeader("Время").setWidth("200px").setFlexGrow(0)
                .setSortable(true).setComparator(Message::timestamp);
        grid.addColumn(m -> MessageFormat.preview(m.key(), 60)).setHeader("Ключ").setWidth("220px").setFlexGrow(0)
                .setResizable(true);
        grid.addColumn(m -> MessageFormat.preview(m.value(), 300)).setHeader("Значение").setFlexGrow(1)
                .setResizable(true);
        grid.addColumn(m -> m.headers().isEmpty() ? "" : String.valueOf(m.headers().size()))
                .setHeader("Hdr").setWidth("60px").setFlexGrow(0);
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        grid.setSizeFull();
        grid.addItemClickListener(e -> openDetails(e.getItem()));

        add(row1, row2, grid);
        expand(grid);
        updateVisibility();
    }

    public void setPartitions(int count) {
        List<String> items = new ArrayList<>();
        items.add(ALL);
        IntStream.range(0, count).forEach(i -> items.add(String.valueOf(i)));
        partition.setItems(items);
        partition.setValue(ALL);
    }

    private void updateVisibility() {
        offset.setVisible(startFrom.getValue() == StartFrom.OFFSET);
        timestamp.setVisible(startFrom.getValue() == StartFrom.TIMESTAMP);
    }

    private void read() {
        Long off = null;
        if (startFrom.getValue() == StartFrom.OFFSET) {
            try {
                off = Long.parseLong(offset.getValue().trim());
            } catch (NumberFormatException e) {
                Ui.warn("Офсет должен быть числом");
                return;
            }
        }
        if (startFrom.getValue() == StartFrom.TIMESTAMP && timestamp.getValue() == null) {
            Ui.warn("Укажите время");
            return;
        }
        BrowseRequest req = new BrowseRequest(
                topic,
                ALL.equals(partition.getValue()) ? null : Integer.valueOf(partition.getValue()),
                startFrom.getValue(),
                off,
                timestamp.getValue() == null ? null : timestamp.getValue().atZone(ZoneId.systemDefault()).toInstant(),
                limit.getValue() == null ? 100 : limit.getValue(),
                keyFilter.getValue(), valueFilter.getValue(), headerFilter.getValue());

        String user = CurrentUser.name();
        load.setEnabled(false);
        status.setText("читаю…");
        Ui.background(this, () -> browse.browse(user, req), (BrowseResult r) -> {
            grid.setItems(r.messages());
            String s = "найдено " + r.messages().size() + ", просмотрено " + r.scanned() + ", " + r.elapsedMs() + " мс";
            if (r.timedOut()) s += " · остановлено по таймауту";
            if (r.truncated()) s += " · есть ещё сообщения";
            status.setText(s);
        }, () -> {
            load.setEnabled(true);
            if ("читаю…".equals(status.getText())) status.setText("");
        });
    }

    private void openDetails(Message m) {
        Format f = format.getValue();
        Dialog d = new Dialog();
        d.setHeaderTitle(m.topic() + " · partition " + m.partition() + " · offset " + m.offset());
        d.setWidth("min(1100px, 96vw)");
        d.setResizable(true);
        d.setDraggable(true);

        TextField meta = new TextField("Время / тип");
        meta.setValue(Ui.ts(m.timestamp()) + "  (" + m.timestampType() + ")");
        meta.setReadOnly(true);
        meta.setWidthFull();

        TextField key = new TextField("Ключ");
        key.setValue(m.key() == null ? "" : MessageFormat.format(m.key(), f == Format.JSON ? Format.AUTO : f));
        key.setPlaceholder(m.key() == null ? "null" : "");
        key.setReadOnly(true);
        key.setWidthFull();

        TextArea headers = new TextArea("Заголовки");
        headers.setValue(m.headers().stream()
                .map(h -> h.key() + " = " + (h.value() == null ? "null" : MessageFormat.preview(h.value(), 500)))
                .collect(Collectors.joining("\n")));
        headers.setReadOnly(true);
        headers.setWidthFull();
        headers.setVisible(!m.headers().isEmpty());

        TextArea value = new TextArea("Значение (" + (m.value() == null ? "null" : m.value().length + " байт") + ")");
        value.setValue(m.value() == null ? "" : MessageFormat.format(m.value(), f));
        value.setReadOnly(true);
        value.setWidthFull();
        value.setHeight("45vh");
        value.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");

        Select<Format> fmt = new Select<>();
        fmt.setItems(Format.values());
        fmt.setValue(f);
        fmt.addValueChangeListener(e -> {
            value.setValue(m.value() == null ? "" : MessageFormat.format(m.value(), e.getValue()));
        });

        VerticalLayout content = new VerticalLayout(meta, key, headers, value);
        content.setPadding(false);
        d.add(content);

        d.getFooter().add(new Span("Формат:"), fmt);
        if (CurrentUser.hasRole(Roles.OPERATOR) && produce.isEnabled()) {
            Button resend = new Button("Переотправить…", VaadinIcon.PAPERPLANE.create(), e -> {
                ProduceDraft.set(new ProduceDraft(
                        produce.isTopicAllowed(m.topic()) ? m.topic() : null,
                        null,
                        m.key() == null ? null : new String(m.key(), StandardCharsets.UTF_8),
                        m.value() == null ? null : new String(m.value(), StandardCharsets.UTF_8),
                        headersText(m.headers())));
                d.close();
                UI.getCurrent().navigate(KafkaProduceView.class);
            });
            resend.setTooltipText("Открыть форму отправки с копией этого сообщения");
            d.getFooter().add(resend);
        }
        d.getFooter().add(new Button("Закрыть", e -> d.close()));
        d.open();
    }

    private static String headersText(List<Header> headers) {
        return headers.stream()
                .filter(h -> !"x-admin-console-user".equals(h.key()))
                .map(h -> h.key() + "=" + (h.value() == null ? "" : new String(h.value(), StandardCharsets.UTF_8)))
                .collect(Collectors.joining("\n"));
    }
}
