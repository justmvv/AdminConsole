package ru.ops.console.ui.db;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.Sql;
import ru.ops.console.kafka.MessageFormat;
import ru.ops.console.ui.common.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Full row card: all columns, JSON is formatted. */
public class RowDetailsDialog extends Dialog {

    /** @param onEdit null — editing the row is not available */
    public RowDetailsDialog(TableDetails t, Row row, boolean canInsert, Consumer<Row> onCopyToInsert,
                            Consumer<Row> onEdit) {
        setHeaderTitle("Строка " + t.ref().qualified());
        setWidth("min(1000px, 95vw)");
        setResizable(true);
        setDraggable(true);

        Div form = new Div();
        form.getStyle().set("display", "grid").set("grid-template-columns", "minmax(160px, max-content) 1fr")
                .set("gap", "6px 16px").set("align-items", "start");

        for (int i = 0; i < t.columns().size(); i++) {
            ColumnInfo c = t.columns().get(i);
            String v = row.get(i);
            Span label = new Span((c.primaryKey() ? "🔑 " : "") + c.name());
            label.getElement().setAttribute("title", c.dataType());
            label.getStyle().set("font-weight", "500").set("padding-top", "8px");

            String shown = v == null ? "" : (c.isJson() ? MessageFormat.prettyJson(v) : v);
            if (shown.length() > 80 || shown.contains("\n")) {
                TextArea ta = new TextArea();
                ta.setValue(shown);
                ta.setReadOnly(true);
                ta.setWidthFull();
                ta.setMaxHeight("300px");
                ta.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)");
                form.add(label, ta);
            } else {
                TextField tf = new TextField();
                tf.setValue(shown);
                tf.setPlaceholder(v == null ? "NULL" : "");
                tf.setReadOnly(true);
                tf.setWidthFull();
                form.add(label, tf);
            }
        }
        add(form);

        Button asInsert = new Button("Как INSERT", VaadinIcon.CODE.create(),
                e -> Ui.showText("INSERT для этой строки", insertSql(t, row)));
        asInsert.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        getFooter().add(asInsert);

        if (canInsert) {
            Button copy = new Button("Создать копию…", VaadinIcon.COPY.create(), e -> {
                close();
                onCopyToInsert.accept(row);
            });
            copy.setTooltipText("Открыть форму вставки, заполненную значениями этой строки (ключи — DEFAULT)");
            copy.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            getFooter().add(copy);
        }
        if (onEdit != null) {
            Button edit = new Button("Изменить…", VaadinIcon.EDIT.create(), e -> {
                close();
                onEdit.accept(row);
            });
            edit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            getFooter().add(edit);
        }
        getFooter().add(new Button("Закрыть", e -> close()));
    }

    static String insertSql(TableDetails t, Row row) {
        List<String> cols = new ArrayList<>();
        List<String> vals = new ArrayList<>();
        for (int i = 0; i < t.columns().size(); i++) {
            ColumnInfo c = t.columns().get(i);
            if (c.isGeneratedAlways()) continue;
            cols.add(Sql.ident(c.name()));
            vals.add(row.get(i) == null ? "NULL" : Sql.literal(row.get(i)));
        }
        return "insert into " + t.ref().sql() + " (" + String.join(", ", cols) + ")\nvalues ("
                + String.join(", ", vals) + ");";
    }
}
