package ru.ops.console.ui.kafka;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.kafka.KafkaModel.Header;
import ru.ops.console.kafka.KafkaModel.ProduceRequest;
import ru.ops.console.kafka.KafkaModel.ProduceResult;
import ru.ops.console.kafka.KafkaProduceService;
import ru.ops.console.kafka.MessageFormat;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishes a message to Kafka (OPERATOR role, allowed topics only).
 */
@RequiresFeature(Feature.KAFKA)
@Route(value = "kafka/produce", layout = MainLayout.class)
@PageTitle("Kafka: отправка")
@RolesAllowed(Roles.OPERATOR)
public class KafkaProduceView extends VerticalLayout implements BeforeEnterObserver {

    private final KafkaProduceService produce;

    private final ComboBox<String> topic = new ComboBox<>("Топик");
    private final IntegerField partition = new IntegerField("Партиция (пусто — по ключу)");
    private final TextField key = new TextField("Ключ");
    private final TextArea headers = new TextArea("Заголовки (key=value, по одному на строку)");
    private final TextArea value = new TextArea("Значение");
    private final TextField reason = new TextField("Обоснование (номер заявки / инцидента)");

    public KafkaProduceView(KafkaProduceService produce) {
        this.produce = produce;
        setMaxWidth("1100px");

        if (!produce.isEnabled()) {
            add(new H2("Отправка сообщений"),
                    new Paragraph("Отправка выключена: пуст console.kafka.produce.allowed-topics или enabled=false."));
            return;
        }

        topic.setWidthFull();
        topic.setPlaceholder("Выберите топик из разрешённых");
        partition.setWidth("260px");
        partition.setMin(0);
        key.setWidthFull();
        headers.setWidthFull();
        headers.setHeight("100px");
        value.setWidthFull();
        value.setHeight("40vh");
        value.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        reason.setWidthFull();
        reason.setRequiredIndicatorVisible(produce.isReasonRequired());

        Button prettify = new Button("Проверить / форматировать JSON", VaadinIcon.CODE.create(), e -> {
            if (MessageFormat.isJson(value.getValue())) {
                value.setValue(MessageFormat.prettyJson(value.getValue()));
                Ui.ok("JSON корректен");
            } else {
                Ui.warn("Значение не является корректным JSON");
            }
        });
        prettify.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button send = new Button("Отправить", VaadinIcon.PAPERPLANE.create(), e -> confirm());
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        HorizontalLayout topRow = new HorizontalLayout(topic, partition);
        topRow.setWidthFull();
        topRow.expand(topic);
        topRow.setAlignItems(FlexComponent.Alignment.BASELINE);

        HorizontalLayout actions = new HorizontalLayout(prettify, send);

        add(new H2("Отправка сообщения в Kafka"), topRow, key, headers, value, reason, actions);

        Ui.background(this, produce::allowedTopics, topics -> {
            String current = topic.getValue();
            topic.setItems(topics);
            if (current != null && topics.contains(current)) topic.setValue(current);
        }, null);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        ProduceDraft draft = ProduceDraft.take();
        if (draft == null || !produce.isEnabled()) return;
        if (draft.topic() != null) {
            topic.setItems(draft.topic());   // replaced by the full list once loaded
            topic.setValue(draft.topic());
        }
        partition.setValue(draft.partition());
        key.setValue(draft.key() == null ? "" : draft.key());
        headers.setValue(draft.headers() == null ? "" : draft.headers());
        String v = draft.value() == null ? "" : draft.value();
        value.setValue(MessageFormat.isJson(v) ? MessageFormat.prettyJson(v) : v);
    }

    private List<Header> parseHeaders() {
        List<Header> result = new ArrayList<>();
        String text = headers.getValue();
        if (text == null || text.isBlank()) return result;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("Строка заголовка без '=': " + line);
            result.add(new Header(line.substring(0, eq).trim(),
                    line.substring(eq + 1).getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }

    private void confirm() {
        if (topic.getValue() == null) {
            Ui.warn("Выберите топик");
            return;
        }
        if (produce.isReasonRequired() && reason.getValue().isBlank()) {
            Ui.warn("Укажите обоснование");
            reason.focus();
            return;
        }
        List<Header> hdrs;
        try {
            hdrs = parseHeaders();
        } catch (IllegalArgumentException ex) {
            Ui.warn(ex.getMessage());
            return;
        }
        ProduceRequest req = new ProduceRequest(topic.getValue(), partition.getValue(), key.getValue(),
                value.getValue(), hdrs, reason.getValue());

        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Отправить сообщение?");
        dialog.setText("Топик: " + req.topic()
                + (req.partition() != null ? ", партиция " + req.partition() : "")
                + (req.key() == null || req.key().isEmpty() ? ", без ключа" : ", ключ «" + req.key() + "»")
                + ". Размер значения: " + (req.value() == null ? 0 : req.value().getBytes(StandardCharsets.UTF_8).length)
                + " байт. Сообщение получат рабочие потребители топика.");
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.setConfirmText("Отправить");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            try {
                ProduceResult r = produce.send(req);
                Ui.ok("Отправлено: " + r.topic() + " / partition " + r.partition() + " / offset " + r.offset());
            } catch (Exception ex) {
                Ui.error(ex);
            }
        });
        dialog.open();
    }
}
