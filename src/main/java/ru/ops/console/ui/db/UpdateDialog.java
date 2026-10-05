package ru.ops.console.ui.db;

import com.vaadin.flow.component.Component;
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
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import ru.ops.console.db.DbConflictException;
import ru.ops.console.db.DbModel.ColumnChange;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.db.DbUpdateService;
import ru.ops.console.ui.common.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single-row update: form of the allowed columns → "before / after" → justification → save.
 * If the application changed the row meanwhile, the current values are shown and the user is asked to review again.
 */
public class UpdateDialog extends Dialog {

    private final TableDetails table;
    private final List<ColumnInfo> editable;
    private final DbUpdateService updates;
    private final Runnable onUpdated;
    private final List<Field> fields = new ArrayList<>();
    private Row original;

    /** Form field: value + NULL checkbox (for nullable columns). */
    record Field(ColumnInfo column, HasValue<?, String> input, Checkbox isNull) {
        boolean nullSelected() {
            return isNull != null && isNull.getValue();
        }

        ColumnValue toValue() {
            return nullSelected()
                    ? new ColumnValue(column.name(), ValueMode.NULL, null)
                    : new ColumnValue(column.name(), ValueMode.VALUE, input.getValue() == null ? "" : input.getValue());
        }
    }

    public UpdateDialog(TableDetails table, List<ColumnInfo> editable, Row row, DbUpdateService updates,
                        Runnable onUpdated) {
        this.table = table;
        this.editable = editable;
        this.updates = updates;
        this.onUpdated = onUpdated;
        this.original = row;
        setHeaderTitle("Изменение строки " + table.ref().qualified() + " · " + keyText());
        setWidth("min(1000px, 96vw)");
        setResizable(true);
        setDraggable(true);
        setCloseOnOutsideClick(false);
        showForm(null);
    }

    private String keyText() {
        return table.primaryKey().stream().map(pk -> pk + " = " + original.get(table.indexOf(pk)))
                .collect(Collectors.joining(", "));
    }

    // -------------------------------------------------------------------- form

    /** @param keep values previously entered by the user (kept when coming back from the review step) */
    private void showForm(List<ColumnValue> keep) {
        removeAll();
        getFooter().removeAll();
        fields.clear();

        Div form = new Div();
        form.getStyle().set("display", "grid")
                .set("grid-template-columns", "minmax(180px, max-content) 1fr max-content")
                .set("gap", "6px 12px").set("align-items", "start");
        for (ColumnInfo c : editable) {
            String current = original.get(table.indexOf(c.name()));
            HasValue<?, String> input = ValueEditors.input(c);
            Checkbox isNull = c.notNull() ? null : new Checkbox("NULL");
            input.setValue(ValueEditors.toInput(c, current));
            if (isNull != null) {
                isNull.setValue(current == null);
                ((HasEnabled) input).setEnabled(current != null);
                isNull.addValueChangeListener(e -> ((HasEnabled) input).setEnabled(!e.getValue()));
            }
            Field f = new Field(c, input, isNull);
            if (keep != null) restore(f, keep);
            fields.add(f);
            Component nullCell = isNull != null ? isNull : new Div();
            form.add(ValueEditors.label(c),
                    ValueEditors.withHelpers(c, input, () -> { if (isNull != null) isNull.setValue(false); }),
                    nullCell);
        }

        List<String> readOnly = table.columns().stream().map(ColumnInfo::name)
                .filter(n -> editable.stream().noneMatch(c -> c.name().equals(n))).toList();
        Paragraph hint = new Paragraph("Изменить можно только перечисленные колонки. Остальные ("
                + String.join(", ", readOnly) + ") запрещены настройками консоли или правами БД, "
                + "ключ и вычисляемые колонки не меняются никогда.");
        hint.getStyle().set("font-size", "var(--lumo-font-size-s)").set("color", "var(--lumo-secondary-text-color)");
        add(form, hint);

        Button next = new Button("Далее: проверить изменения", VaadinIcon.ARROW_RIGHT.create(), e -> showReview());
        next.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        getFooter().add(new Button("Отмена", e -> close()), next);
    }

    private static void restore(Field f, List<ColumnValue> keep) {
        for (ColumnValue v : keep) {
            if (!v.column().equals(f.column().name())) continue;
            if (v.mode() == ValueMode.NULL && f.isNull() != null) {
                f.isNull().setValue(true);
            } else {
                if (f.isNull() != null) f.isNull().setValue(false);
                f.input().setValue(v.value());
            }
        }
    }

    /** Only the columns that really changed (JSON is compared ignoring formatting). */
    private List<ColumnValue> collectChanged() {
        List<ColumnValue> changed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Field f : fields) {
            String current = original.get(table.indexOf(f.column().name()));
            if (f.nullSelected()) {
                if (current != null) changed.add(f.toValue());
                continue;
            }
            String value = f.input().getValue() == null ? "" : f.input().getValue();
            if (ValueEditors.sameAsDb(f.column(), current, value)) continue;
            String error = ValueEditors.validate(f.column(), value);
            if (error != null) errors.add(error);
            changed.add(f.toValue());
        }
        if (!errors.isEmpty()) {
            Ui.error("Исправьте поля:\n" + String.join("\n", errors));
            return null;
        }
        return changed;
    }

    // ------------------------------------------------------------------ review

    private void showReview() {
        List<ColumnValue> values = collectChanged();
        if (values == null) return;
        if (values.isEmpty()) {
            Ui.warn("Нет изменений");
            return;
        }
        List<ColumnChange> changes;
        String sql;
        try {
            changes = updates.changes(table, original, values);
            sql = updates.previewRow(table, original, values);
        } catch (Exception e) {
            Ui.error(e);
            return;
        }

        removeAll();
        getFooter().removeAll();

        Grid<ColumnChange> diff = new Grid<>();
        diff.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_COLUMN_BORDERS, GridVariant.LUMO_WRAP_CELL_CONTENT);
        diff.addColumn(ColumnChange::column).setHeader("Колонка").setAutoWidth(true).setFlexGrow(0);
        diff.addColumn(ch -> shown(ch.oldValue())).setHeader("Было");
        diff.addColumn(ch -> shown(ch.shownNew())).setHeader("Станет");
        diff.setItems(changes);
        diff.setAllRowsVisible(true);

        TextArea sqlArea = new TextArea();
        sqlArea.setValue(sql);
        sqlArea.setReadOnly(true);
        sqlArea.setWidthFull();
        sqlArea.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
        Details sqlDetails = new Details("Показать SQL", sqlArea);

        TextField reason = new TextField("Обоснование (номер заявки / инцидента)");
        reason.setWidthFull();
        reason.setRequiredIndicatorVisible(updates.isReasonRequired());

        Paragraph warn = new Paragraph("Строка будет заблокирована и сверена с показанными значениями: если её "
                + "успели изменить, сохранения не будет. Изменение фиксируется в журнале аудита вместе с прежними "
                + "значениями и обоснованием.");
        warn.getStyle().set("color", "var(--lumo-error-text-color)");

        VerticalLayout step = new VerticalLayout(diff, sqlDetails, reason, warn);
        step.setPadding(false);
        add(step);

        Button back = new Button("Назад", VaadinIcon.ARROW_LEFT.create(), e -> showForm(values));
        Button save = new Button("Сохранить изменения", VaadinIcon.CHECK.create());
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        save.setDisableOnClick(true);
        save.addClickListener(e -> {
            try {
                updates.updateRow(table, original, values, reason.getValue());
                close();
                Ui.ok("Строка изменена: " + table.ref().qualified() + " · " + keyText());
                onUpdated.run();
            } catch (DbConflictException ex) {
                Ui.error(ex);
                onUpdated.run();
                if (ex.current() == null) {
                    close();
                } else {
                    // Show the form again — based on the current values, keeping what the user entered
                    original = ex.current();
                    showForm(values);
                }
            } catch (Exception ex) {
                save.setEnabled(true);
                Ui.error(ex);
            }
        });
        getFooter().add(back, save);
        reason.focus();
    }

    static String shown(String v) {
        return v == null ? "NULL" : v;
    }
}
