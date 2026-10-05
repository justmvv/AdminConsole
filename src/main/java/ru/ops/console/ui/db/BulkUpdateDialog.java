package ru.ops.console.ui.db;

import com.vaadin.flow.component.HasEnabled;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import ru.ops.console.db.DbConflictException;
import ru.ops.console.db.DbModel.BulkPreview;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.Filter;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.db.DbUpdateService;
import ru.ops.console.ui.common.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Updates all rows matching the table's current filters.
 * Steps: choose columns and values → row count and samples → confirm by typing the row count → execute.
 */
public class BulkUpdateDialog extends Dialog {

    private final TableDetails table;
    private final List<ColumnInfo> editable;
    private final List<Filter> filters;
    private final DbUpdateService updates;
    private final Runnable onUpdated;
    private final List<Field> fields = new ArrayList<>();

    private record Field(Checkbox use, UpdateDialog.Field value) {
    }

    public BulkUpdateDialog(TableDetails table, List<ColumnInfo> editable, List<Filter> filters,
                            DbUpdateService updates, Runnable onUpdated) {
        this.table = table;
        this.editable = editable;
        this.filters = List.copyOf(filters);
        this.updates = updates;
        this.onUpdated = onUpdated;
        setHeaderTitle("Изменение по фильтру: " + table.ref().qualified());
        setWidth("min(1100px, 96vw)");
        setResizable(true);
        setDraggable(true);
        setCloseOnOutsideClick(false);
        buildForm();
        showForm();
    }

    private Span filtersText() {
        Span s = new Span("Фильтр: " + filters.stream().map(Filter::toString).collect(Collectors.joining("  И  ")));
        s.getStyle().set("font-weight", "500");
        return s;
    }

    // -------------------------------------------------------------------- form

    private void buildForm() {
        for (ColumnInfo c : editable) {
            Checkbox use = new Checkbox();
            HasValue<?, String> input = ValueEditors.input(c);
            Checkbox isNull = c.notNull() ? null : new Checkbox("NULL");
            Runnable sync = () -> {
                boolean on = use.getValue();
                ((HasEnabled) input).setEnabled(on && (isNull == null || !isNull.getValue()));
                if (isNull != null) isNull.setEnabled(on);
            };
            use.addValueChangeListener(e -> sync.run());
            if (isNull != null) isNull.addValueChangeListener(e -> sync.run());
            sync.run();
            fields.add(new Field(use, new UpdateDialog.Field(c, input, isNull)));
        }
    }

    private void showForm() {
        removeAll();
        getFooter().removeAll();

        Div form = new Div();
        form.getStyle().set("display", "grid")
                .set("grid-template-columns", "max-content minmax(180px, max-content) 1fr max-content")
                .set("gap", "6px 12px").set("align-items", "start");
        for (Field f : fields) {
            ColumnInfo c = f.value().column();
            Checkbox isNull = f.value().isNull();
            form.add(f.use(), ValueEditors.label(c),
                    ValueEditors.withHelpers(c, f.value().input(), () -> {
                        f.use().setValue(true);
                        if (isNull != null) isNull.setValue(false);
                    }),
                    isNull != null ? isNull : new Div());
        }

        Paragraph hint = new Paragraph("Отметьте колонки, которые нужно изменить. Новое значение будет одинаковым "
                + "для всех строк под фильтром. Не более " + updates.maxRows() + " строк за раз.");
        hint.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        add(filtersText(), form, hint);

        Button next = new Button("Далее: сколько строк изменится", VaadinIcon.ARROW_RIGHT.create(),
                e -> showPreview());
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(new Button("Отмена", e -> close()), next);
    }

    private List<ColumnValue> collect() {
        List<ColumnValue> values = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Field f : fields) {
            if (!f.use().getValue()) continue;
            ColumnValue v = f.value().toValue();
            if (v.mode() == ValueMode.VALUE) {
                String error = ValueEditors.validate(f.value().column(), v.value());
                if (error != null) errors.add(error);
            }
            values.add(v);
        }
        if (values.isEmpty()) errors.add("Отметьте хотя бы одну колонку");
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
        BulkPreview preview;
        try {
            preview = updates.previewBulk(table, filters, values);
        } catch (Exception e) {
            Ui.error(e);
            return;
        }

        removeAll();
        getFooter().removeAll();
        Button back = new Button("Назад", VaadinIcon.ARROW_LEFT.create(), e -> showForm());
        getFooter().add(back);

        long count = preview.count();
        Span summary = new Span("Под фильтр попадает строк: " + String.format("%,d", count));
        summary.getStyle().set("font-size", "var(--lumo-font-size-l)").set("font-weight", "600");

        Span setText = new Span("Будет установлено: " + values.stream()
                .map(v -> v.column() + " = " + (v.mode() == ValueMode.NULL ? "NULL" : "«" + v.value() + "»"))
                .collect(Collectors.joining(", ")));

        if (count == 0 || count > updates.maxRows()) {
            Paragraph stop = new Paragraph(count == 0
                    ? "Изменять нечего — уточните фильтр."
                    : "Это больше лимита " + updates.maxRows() + " строк за одно изменение. Уточните фильтр.");
            stop.getStyle().set("color", "var(--lumo-error-text-color)");
            add(filtersText(), summary, stop);
            return;
        }

        Grid<Row> sample = new Grid<>();
        sample.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_COLUMN_BORDERS);
        for (int i = 0; i < preview.columns().size(); i++) {
            final int idx = i;
            String col = preview.columns().get(i);
            boolean changing = values.stream().anyMatch(v -> v.column().equals(col));
            sample.addColumn(r -> UpdateDialog.shown(r.get(idx)))
                    .setHeader(changing ? col + " (сейчас)" : "🔑 " + col).setResizable(true);
        }
        sample.setItems(preview.sample());
        sample.setAllRowsVisible(true);
        Span sampleTitle = new Span(count > preview.sample().size()
                ? "Первые " + preview.sample().size() + " строк:" : "Строки:");

        TextArea sqlArea = new TextArea();
        sqlArea.setValue(preview.sql());
        sqlArea.setReadOnly(true);
        sqlArea.setWidthFull();
        sqlArea.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        Details sqlDetails = new Details("Показать SQL", sqlArea);

        TextField reason = new TextField("Обоснование (номер заявки / инцидента)");
        reason.setWidthFull();
        reason.setRequiredIndicatorVisible(updates.isReasonRequired());

        TextField confirm = new TextField("Для подтверждения введите число строк: " + count);
        confirm.setValueChangeMode(ValueChangeMode.EAGER);

        Paragraph warn = new Paragraph("Строки будут заблокированы. Если под фильтр к этому моменту попадёт другое "
                + "число строк, изменения не будет. Прежние значения всех строк сохраняются в журнале аудита.");
        warn.getStyle().set("color", "var(--lumo-error-text-color)");

        VerticalLayout step = new VerticalLayout(filtersText(), summary, setText, sampleTitle, sample, sqlDetails,
                reason, confirm, warn);
        step.setPadding(false);
        add(step);

        Button exec = new Button("Изменить " + count + " строк", VaadinIcon.CHECK.create());
        exec.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        exec.setEnabled(false);
        confirm.addValueChangeListener(e -> exec.setEnabled(String.valueOf(count).equals(e.getValue().trim())));
        exec.setDisableOnClick(true);
        exec.addClickListener(e -> {
            try {
                int rows = updates.updateBulk(table, filters, values, count, reason.getValue()).rows();
                close();
                Ui.ok("Изменено строк: " + rows + " в " + table.ref().qualified());
                onUpdated.run();
            } catch (DbConflictException ex) {
                Ui.error(ex);
                onUpdated.run();
                showPreview();
            } catch (Exception ex) {
                exec.setEnabled(true);
                Ui.error(ex);
            }
        });
        getFooter().add(exec);
        reason.focus();
    }
}
