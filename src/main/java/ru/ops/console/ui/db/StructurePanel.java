package ru.ops.console.ui.db;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ConstraintInfo;
import ru.ops.console.db.DbModel.IndexInfo;
import ru.ops.console.db.DbModel.TableDetails;

/** "Structure" tab: columns, constraints, indexes. */
public class StructurePanel extends VerticalLayout {

    public StructurePanel(TableDetails t) {
        setPadding(false);
        setWidthFull();

        Grid<ColumnInfo> cols = new Grid<>();
        cols.addColumn(ColumnInfo::position).setHeader("#").setWidth("60px").setFlexGrow(0);
        cols.addColumn(c -> (c.primaryKey() ? "🔑 " : "") + c.name()).setHeader("Колонка").setAutoWidth(true);
        cols.addColumn(ColumnInfo::dataType).setHeader("Тип").setAutoWidth(true);
        cols.addColumn(c -> c.notNull() ? "NOT NULL" : "").setHeader("Null").setAutoWidth(true);
        cols.addColumn(c -> {
            if ("a".equals(c.identity())) return "IDENTITY ALWAYS";
            if ("d".equals(c.identity())) return "IDENTITY BY DEFAULT";
            if ("s".equals(c.generated())) return "GENERATED: " + c.defaultExpr();
            return c.defaultExpr() == null ? "" : c.defaultExpr();
        }).setHeader("По умолчанию").setAutoWidth(true);
        cols.addColumn(c -> c.isEnum() ? String.join(", ", c.enumValues()) : "").setHeader("Значения enum")
                .setAutoWidth(true);
        cols.addColumn(c -> c.comment() == null ? "" : c.comment()).setHeader("Комментарий").setFlexGrow(1);
        cols.setItems(t.columns());
        cols.setAllRowsVisible(true);
        cols.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);

        Grid<ConstraintInfo> cons = new Grid<>();
        cons.addColumn(ConstraintInfo::type).setHeader("Тип").setAutoWidth(true).setFlexGrow(0);
        cons.addColumn(ConstraintInfo::name).setHeader("Имя").setAutoWidth(true).setFlexGrow(0);
        cons.addColumn(ConstraintInfo::definition).setHeader("Определение").setFlexGrow(1);
        cons.setItems(t.constraints());
        cons.setAllRowsVisible(true);
        cons.addThemeVariants(GridVariant.LUMO_COMPACT);

        Grid<IndexInfo> idx = new Grid<>();
        idx.addColumn(IndexInfo::name).setHeader("Индекс").setAutoWidth(true).setFlexGrow(0);
        idx.addColumn(IndexInfo::definition).setHeader("Определение").setFlexGrow(1);
        idx.setItems(t.indexes());
        idx.setAllRowsVisible(true);
        idx.addThemeVariants(GridVariant.LUMO_COMPACT);

        add(new H4("Колонки"), cols, new H4("Ограничения"), cons, new H4("Индексы"), idx);
    }
}
