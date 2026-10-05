package ru.ops.console.ui.db;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasEnabled;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import ru.ops.console.db.DbDataService;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.InsertResult;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.ui.common.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Row insert: form built from metadata → SQL preview → justification → execution.
 * Each column has a mode: value / NULL / DEFAULT (the column is omitted from the INSERT).
 */
public class InsertDialog extends Dialog {

    private final TableDetails table;
    private final DbDataService data;
    private final Consumer<InsertResult> onInserted;
    private final List<Field> fields = new ArrayList<>();
    private VerticalLayout formContainer;

    private record Field(ColumnInfo column, Select<ValueMode> mode, HasValue<?, String> input) {
        ColumnValue toValue() {
            String v = input.getValue();
            return new ColumnValue(column.name(), mode.getValue(), v == null ? "" : v);
        }
    }

    public InsertDialog(TableDetails table, Row template, DbDataService data, Consumer<InsertResult> onInserted) {
        this.table = table;
        this.data = data;
        this.onInserted = onInserted;
        setHeaderTitle((template == null ? "Новая строка: " : "Копия строки: ") + table.ref().qualified());
        setWidth("min(1100px, 96vw)");
        setResizable(true);
        setDraggable(true);
        setCloseOnOutsideClick(false);
        showForm(template);
    }

    // -------------------------------------------------------------------- form

    private void showForm(Row template) {
        removeAll();
        getFooter().removeAll();
        fields.clear();

        Div form = new Div();
        form.getStyle().set("display", "grid")
                .set("grid-template-columns", "minmax(180px, max-content) 130px 1fr")
                .set("gap", "6px 12px").set("align-items", "start");

        for (int i = 0; i < table.columns().size(); i++) {
            ColumnInfo c = table.columns().get(i);
            String templateValue = template == null ? null : template.get(i);
            form.add(ValueEditors.label(c));
            Field f = createField(c, template != null, templateValue);
            form.add(f.mode(), inputWithHelpers(f));
            fields.add(f);
        }

        Paragraph hint = new Paragraph("DEFAULT — колонка не указывается в INSERT (сработает значение по умолчанию/"
                + "последовательность). Значения передаются в PostgreSQL как текст и приводятся к типу колонки "
                + "сервером: даты — 2026-09-25 12:00:00+03, jsonb — корректный JSON, массивы — {a,b}.");
        hint.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        formContainer = new VerticalLayout(form, hint);
        formContainer.setPadding(false);
        add(formContainer);

        Button preview = new Button("Далее: проверить SQL", VaadinIcon.ARROW_RIGHT.create(), e -> showPreview());
        preview.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(new Button("Отмена", e -> close()), preview);
    }

    private Field createField(ColumnInfo c, boolean fromTemplate, String templateValue) {
        Select<ValueMode> mode = new Select<>();
        mode.setItems(ValueMode.values());
        mode.setItemLabelGenerator(m -> switch (m) {
            case VALUE -> "значение";
            case NULL -> "NULL";
            case DEFAULT -> "DEFAULT";
        });
        mode.setWidthFull();

        HasValue<?, String> input = ValueEditors.input(c);

        // Default mode
        ValueMode initial;
        if (c.isGeneratedAlways()) {
            initial = ValueMode.DEFAULT;
            mode.setEnabled(false);
        } else if (fromTemplate) {
            boolean key = c.primaryKey() || !c.identity().isEmpty();
            if (key && c.hasDefault()) {
                initial = ValueMode.DEFAULT;
            } else if (templateValue == null) {
                initial = c.notNull() ? ValueMode.DEFAULT : ValueMode.NULL;
            } else {
                initial = ValueMode.VALUE;
                input.setValue(ValueEditors.toInput(c, templateValue));
            }
        } else if (c.hasDefault() || !c.notNull()) {
            initial = ValueMode.DEFAULT;
        } else {
            initial = ValueMode.VALUE;
        }
        mode.setValue(initial);

        final HasValue<?, String> in = input;
        ((HasEnabled) input).setEnabled(initial == ValueMode.VALUE);
        mode.addValueChangeListener(e -> ((HasEnabled) in).setEnabled(e.getValue() == ValueMode.VALUE));
        return new Field(c, mode, input);
    }

    private Component inputWithHelpers(Field f) {
        return ValueEditors.withHelpers(f.column(), f.input(), () -> f.mode().setValue(ValueMode.VALUE));
    }

    // -------------------------------------------------------------- validation

    private List<ColumnValue> collect() {
        List<ColumnValue> values = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Field f : fields) {
            ColumnValue v = f.toValue();
            ColumnInfo c = f.column();
            if (v.mode() == ValueMode.NULL && c.notNull()) errors.add(c.name() + ": NOT NULL");
            if (v.mode() == ValueMode.VALUE) {
                String error = ValueEditors.validate(c, v.value());
                if (error != null) errors.add(error);
            }
            values.add(v);
        }
        if (!errors.isEmpty()) {
            Ui.error("Исправьте поля:\n" + String.join("\n", errors));
            return null;
        }
        return values;
    }

    // ----------------------------------------------------------------- preview

    private void showPreview() {
        List<ColumnValue> values = collect();
        if (values == null) return;
        String sql;
        try {
            sql = data.previewInsert(table, values);
        } catch (Exception e) {
            Ui.error(e);
            return;
        }

        // Hide the form, keeping the entered values
        formContainer.setVisible(false);

        TextArea sqlArea = new TextArea("Будет выполнено");
        sqlArea.setValue(sql);
        sqlArea.setReadOnly(true);
        sqlArea.setWidthFull();
        sqlArea.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");

        TextField reason = new TextField("Обоснование (номер заявки / инцидента)");
        reason.setWidthFull();
        reason.setRequiredIndicatorVisible(true);

        Paragraph warn = new Paragraph("Операция выполняется в отдельной транзакции и фиксируется в журнале аудита "
                + "вместе с обоснованием и вставленной строкой.");
        warn.getStyle().set("color", "var(--lumo-error-text-color)");

        VerticalLayout step2 = new VerticalLayout(sqlArea, reason, warn);
        step2.setPadding(false);
        add(step2);

        getFooter().removeAll();
        Button back = new Button("Назад", VaadinIcon.ARROW_LEFT.create(), e -> {
            remove(step2);
            formContainer.setVisible(true);
            restoreFormFooter();
        });
        Button exec = new Button("Выполнить INSERT", VaadinIcon.CHECK.create());
        exec.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        exec.setDisableOnClick(true);
        exec.addClickListener(e -> {
            try {
                InsertResult result = data.insert(table, values, reason.getValue());
                close();
                Ui.ok("Строка вставлена в " + table.ref().qualified());
                onInserted.accept(result);
                showResult(result);
            } catch (Exception ex) {
                exec.setEnabled(true);
                Ui.error(ex);
            }
        });
        getFooter().add(back, exec);
        reason.focus();
    }

    private void restoreFormFooter() {
        getFooter().removeAll();
        Button preview = new Button("Далее: проверить SQL", VaadinIcon.ARROW_RIGHT.create(), e -> showPreview());
        preview.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(new Button("Отмена", e -> close()), preview);
    }

    private static void showResult(InsertResult r) {
        StringBuilder sb = new StringBuilder("-- Вставленная строка (RETURNING *)\n");
        for (int i = 0; i < r.columns().size(); i++) {
            sb.append(r.columns().get(i)).append(" = ").append(r.inserted().get(i) == null ? "NULL" : r.inserted().get(i))
                    .append('\n');
        }
        sb.append("\n-- Выполненный SQL\n").append(r.sqlPreview());
        Ui.showText("Результат вставки", sb.toString());
    }
}
