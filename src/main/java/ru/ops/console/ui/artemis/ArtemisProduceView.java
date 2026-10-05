package ru.ops.console.ui.artemis;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.artemis.ArtemisModel.ProduceRequest;
import ru.ops.console.artemis.ArtemisModel.ProduceResult;
import ru.ops.console.artemis.ArtemisProduceService;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.kafka.MessageFormat;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Publishes a text message to Artemis (OPERATOR role, allowed addresses only). */
@RequiresFeature(Feature.ARTEMIS)
@Route(value = "artemis/produce", layout = MainLayout.class)
@PageTitle("Artemis: отправка")
@RolesAllowed(Roles.OPERATOR)
public class ArtemisProduceView extends VerticalLayout implements BeforeEnterObserver {

    private static final String BY_ADDRESS = "по настройке адреса";

    private final ArtemisProduceService produce;
    private final ComboBox<String> address = new ComboBox<>("Адрес");
    private final Select<String> routing = new Select<>();
    private final Checkbox durable = new Checkbox("Durable (сохраняется на диске брокера)", true);
    private final TextArea properties = new TextArea("Свойства (имя=значение, по одному на строку)");
    private final TextArea body = new TextArea("Тело (текст, JMS TextMessage)");
    private final TextField reason = new TextField("Обоснование (номер заявки / инцидента)");

    public ArtemisProduceView(ArtemisProduceService produce) {
        this.produce = produce;
        setMaxWidth("1100px");

        if (!produce.isEnabled()) {
            add(new H2("Отправка сообщений"), new Paragraph(
                    "Отправка выключена: пуст console.artemis.produce.allowed-addresses или enabled=false."));
            return;
        }

        address.setWidthFull();
        address.setPlaceholder("Выберите адрес из разрешённых");
        routing.setLabel("Тип маршрутизации");
        routing.setItems(BY_ADDRESS, "ANYCAST", "MULTICAST");
        routing.setValue(BY_ADDRESS);
        routing.setWidth("220px");
        properties.setWidthFull();
        properties.setHeight("100px");
        body.setWidthFull();
        body.setHeight("40vh");
        body.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        reason.setWidthFull();
        reason.setRequiredIndicatorVisible(produce.isReasonRequired());

        Button prettify = new Button("Проверить / форматировать JSON", VaadinIcon.CODE.create(), e -> {
            if (MessageFormat.isJson(body.getValue())) {
                body.setValue(MessageFormat.prettyJson(body.getValue()));
                Ui.ok("JSON корректен");
            } else {
                Ui.warn("Тело не является корректным JSON");
            }
        });
        prettify.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button send = new Button("Отправить", VaadinIcon.PAPERPLANE.create(), e -> confirm());
        send.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);

        HorizontalLayout top = new HorizontalLayout(address, routing);
        top.setWidthFull();
        top.expand(address);
        top.setAlignItems(FlexComponent.Alignment.BASELINE);

        add(new H2("Отправка сообщения в Artemis"), top, durable, properties, body, reason,
                new HorizontalLayout(prettify, send));

        Ui.background(this, produce::allowedAddresses, list -> {
            String current = address.getValue();
            address.setItems(list);
            if (current != null && list.contains(current)) address.setValue(current);
        }, null);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        ArtemisDraft draft = ArtemisDraft.take();
        if (draft == null || !produce.isEnabled()) return;
        if (draft.address() != null) {
            address.setItems(draft.address());   // replaced by the full list once loaded
            address.setValue(draft.address());
        }
        properties.setValue(draft.properties() == null ? "" : draft.properties());
        String b = draft.body() == null ? "" : draft.body();
        body.setValue(MessageFormat.isJson(b) ? MessageFormat.prettyJson(b) : b);
    }

    private Map<String, String> parseProperties() {
        Map<String, String> result = new LinkedHashMap<>();
        String text = properties.getValue();
        if (text == null || text.isBlank()) return result;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("Строка свойства без '=': " + line);
            result.put(line.substring(0, eq).trim(), line.substring(eq + 1));
        }
        return result;
    }

    private void confirm() {
        if (address.getValue() == null) {
            Ui.warn("Выберите адрес");
            return;
        }
        if (produce.isReasonRequired() && reason.getValue().isBlank()) {
            Ui.warn("Укажите обоснование");
            reason.focus();
            return;
        }
        Map<String, String> props;
        try {
            props = parseProperties();
        } catch (IllegalArgumentException ex) {
            Ui.warn(ex.getMessage());
            return;
        }
        ProduceRequest req = new ProduceRequest(address.getValue(),
                BY_ADDRESS.equals(routing.getValue()) ? null : routing.getValue(),
                durable.getValue(), body.getValue(), props, reason.getValue());

        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Отправить сообщение?");
        dialog.setText("Адрес: " + req.address() + (req.routingType() == null ? "" : " (" + req.routingType() + ")")
                + ", свойств: " + props.size() + ", размер тела: "
                + (req.body() == null ? 0 : req.body().getBytes(StandardCharsets.UTF_8).length)
                + " байт. Сообщение получат рабочие потребители.");
        dialog.setCancelable(true);
        dialog.setCancelText("Отмена");
        dialog.setConfirmText("Отправить");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            try {
                ProduceResult r = produce.send(req);
                Ui.ok("Отправлено на " + r.address() + ": " + r.messageId());
            } catch (Exception ex) {
                Ui.error(ex);
            }
        });
        dialog.open();
    }
}
