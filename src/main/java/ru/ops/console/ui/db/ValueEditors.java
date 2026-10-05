package ru.ops.console.ui.db;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.kafka.MessageFormat;
import ru.ops.console.ui.common.Ui;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Type-specific input fields for column values (shared by insert and update). The value is always text. */
final class ValueEditors {

    private ValueEditors() {
    }

    static Component label(ColumnInfo c) {
        Span name = new Span((c.primaryKey() ? "🔑 " : "") + c.name());
        name.getStyle().set("font-weight", "500");
        Span type = new Span(c.dataType() + (c.notNull() ? " · NOT NULL" : ""));
        type.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        VerticalLayout l = new VerticalLayout(name, type);
        l.setPadding(false);
        l.setSpacing(false);
        if (c.comment() != null) l.getElement().setAttribute("title", c.comment());
        return l;
    }

    static HasValue<?, String> input(ColumnInfo c) {
        if (c.isEnum()) {
            ComboBox<String> cb = new ComboBox<>();
            cb.setItems(c.enumValues());
            cb.setWidthFull();
            return cb;
        }
        if (c.isBoolean()) {
            ComboBox<String> cb = new ComboBox<>();
            cb.setItems("true", "false");
            cb.setWidthFull();
            return cb;
        }
        if (c.isLongText()) {
            TextArea ta = new TextArea();
            ta.setWidthFull();
            ta.setMinHeight("60px");
            ta.setMaxHeight("260px");
            ta.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
            return ta;
        }
        TextField tf = new TextField();
        tf.setWidthFull();
        tf.setPlaceholder(c.dataType());
        return tf;
    }

    /** Current DB value → field value (boolean t/f → true/false, JSON — indented). */
    static String toInput(ColumnInfo c, String dbValue) {
        if (dbValue == null) return "";
        if (c.isBoolean()) return "t".equals(dbValue) ? "true" : "f".equals(dbValue) ? "false" : dbValue;
        if (c.isJson()) return MessageFormat.prettyJson(dbValue);
        return dbValue;
    }

    /** Whether the field value equals the DB value (taking JSON and boolean formatting into account). */
    static boolean sameAsDb(ColumnInfo c, String dbValue, String input) {
        if (dbValue == null) return false;
        if (c.isJson()) {
            return MessageFormat.isJson(input)
                    && Objects.equals(MessageFormat.prettyJson(dbValue), MessageFormat.prettyJson(input));
        }
        return Objects.equals(toInput(c, dbValue), input);
    }

    /** Field + helper buttons (UUID, "now", JSON formatting). beforeSet — e.g. switch to "value" mode. */
    static Component withHelpers(ColumnInfo c, HasValue<?, String> input, Runnable beforeSet) {
        HorizontalLayout hl = new HorizontalLayout((Component) input);
        hl.setWidthFull();
        hl.setSpacing(true);
        hl.setAlignItems(FlexComponent.Alignment.START);
        hl.expand((Component) input);
        if (c.isUuid()) {
            hl.add(helper("UUID", () -> UUID.randomUUID().toString(), input, beforeSet));
        }
        if (c.isDateTime() && !"date".equals(c.typeName()) && !"time".equals(c.typeName())) {
            hl.add(helper("сейчас", () -> OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    input, beforeSet));
        }
        if (c.isJson()) {
            hl.add(helper("{ }", () -> {
                String v = input.getValue();
                if (!MessageFormat.isJson(v)) {
                    Ui.warn("Некорректный JSON в колонке " + c.name());
                    return v;
                }
                return MessageFormat.prettyJson(v);
            }, input, beforeSet));
        }
        return hl;
    }

    private static Button helper(String text, Supplier<String> value, HasValue<?, String> input, Runnable beforeSet) {
        Button b = new Button(text, e -> {
            if (beforeSet != null) beforeSet.run();
            input.setValue(value.get());
        });
        b.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        return b;
    }

    /** Validates the entered value; null — all good. */
    static String validate(ColumnInfo c, String value) {
        if (c.isJson() && !MessageFormat.isJson(value)) return c.name() + ": некорректный JSON";
        if (c.notNull() && (value == null || value.isEmpty()) && !"S".equals(c.category())) {
            return c.name() + ": пустое значение";
        }
        return null;
    }
}
