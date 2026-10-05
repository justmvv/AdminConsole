package ru.ops.console.ui.db;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.server.StreamResource;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.db.DbDataService;
import ru.ops.console.db.DbMetadataService;
import ru.ops.console.db.DbUpdateService;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.Filter;
import ru.ops.console.db.DbModel.FilterOp;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.Sort;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.common.Ui;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One table: "Data" and "Structure" tabs.
 */
public class TablePanel extends VerticalLayout {

    private static final int CELL_PREVIEW = 120;

    private final TableDetails table;
    private final DbMetadataService metadata;
    private final DbDataService data;
    private final AuditService audit;
    private final DbUpdateService updates;
    /** Columns the current user may change (empty — editing is not available). */
    private final List<ColumnInfo> editable;
    private Button bulkUpdate;

    private final List<Filter> filters = new ArrayList<>();
    private List<Sort> lastSorts = List.of();
    private final FlexLayout filterChips = new FlexLayout();
    private final Grid<Row> grid = new Grid<>();
    private final Span countLabel = new Span();

    public TablePanel(TableDetails table, DbMetadataService metadata, DbDataService data, DbUpdateService updates,
                      AuditService audit) {
        this.table = table;
        this.updates = updates;
        this.editable = CurrentUser.hasRole(Roles.OPERATOR) ? metadata.updatableColumns(table) : List.of();
        this.metadata = metadata;
        this.data = data;
        this.audit = audit;
        setSizeFull();
        setPadding(true);
        setSpacing(false);

        H3 title = new H3(table.ref().qualified());
        title.getStyle().set("margin", "0");
        HorizontalLayout header = new HorizontalLayout(title,
                Ui.badge(table.info().kind().label(), null),
                Ui.badge("≈ " + DbView.approx(table.info().estimatedRows()) + " строк", "contrast"));
        header.setAlignItems(FlexComponent.Alignment.BASELINE);
        if (table.info().comment() != null) {
            Span comment = new Span(table.info().comment());
            comment.getStyle().set("color", "var(--lumo-secondary-text-color)");
            header.add(comment);
        }

        TabSheet tabs = new TabSheet();
        tabs.setSizeFull();
        tabs.add("Данные", buildDataTab());
        tabs.add("Структура", new StructurePanel(table));

        add(header, tabs);
        expand(tabs);

        audit.record(AuditAction.DB_VIEW, table.ref().qualified(), null, true, Map.of());
    }

    // -------------------------------------------------------------------- data

    private Component buildDataTab() {
        ComboBox<ColumnInfo> column = new ComboBox<>();
        column.setPlaceholder("Колонка");
        column.setItems(table.columns());
        column.setItemLabelGenerator(c -> c.name() + (c.primaryKey() ? " 🔑" : ""));
        column.setWidth("200px");

        Select<FilterOp> op = new Select<>();
        op.setItems(FilterOp.values());
        op.setItemLabelGenerator(FilterOp::label);
        op.setValue(FilterOp.EQ);
        op.setWidth("170px");

        TextField value = new TextField();
        value.setPlaceholder("Значение");
        value.setWidth("260px");
        op.addValueChangeListener(e -> value.setEnabled(e.getValue() != null && e.getValue().needsValue()));
        column.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                value.setPlaceholder(e.getValue().dataType()
                        + (e.getValue().isEnum() ? ": " + String.join(" | ", e.getValue().enumValues()) : ""));
            }
        });

        Button add = new Button("Фильтр", VaadinIcon.FILTER.create(), e -> {
            if (column.getValue() == null) {
                Ui.warn("Выберите колонку");
                return;
            }
            if (op.getValue().needsValue() && (value.getValue() == null || value.getValue().isEmpty())) {
                Ui.warn("Введите значение");
                return;
            }
            filters.add(new Filter(column.getValue().name(), op.getValue(), value.getValue()));
            value.clear();
            onFiltersChanged();
        });
        add.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        add.addClickShortcut(com.vaadin.flow.component.Key.ENTER).listenOn(value);

        Button refresh = new Button(VaadinIcon.REFRESH.create(), e -> grid.getDataProvider().refreshAll());
        refresh.setTooltipText("Обновить");

        Button count = new Button("Посчитать строки", VaadinIcon.CALC.create(), e -> {
            try {
                countLabel.setText("строк: " + String.format("%,d", data.count(table, filters)));
            } catch (Exception ex) {
                Ui.error(ex);
            }
        });
        count.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button sql = new Button("SQL", VaadinIcon.CODE.create(),
                e -> Ui.showText("Выполняемый запрос", data.describeQuery(table, filters, lastSorts)));
        sql.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout toolbar = new HorizontalLayout(column, op, value, add, refresh, count, sql);
        toolbar.setAlignItems(FlexComponent.Alignment.BASELINE);
        toolbar.getStyle().set("flex-wrap", "wrap");

        if (CurrentUser.hasRole(Roles.OPERATOR)) {
            toolbar.add(exportLink());
        }
        if (CurrentUser.hasRole(Roles.OPERATOR) && metadata.isInsertAllowed(table.info())) {
            Button insert = new Button("Вставить строку", VaadinIcon.PLUS.create(), e -> openInsert(null));
            insert.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_PRIMARY);
            toolbar.add(insert);
        }
        if (!editable.isEmpty()) {
            bulkUpdate = new Button("Изменить по фильтру…", VaadinIcon.EDIT.create(), e ->
                    new BulkUpdateDialog(table, editable, filters, updates, this::refresh).open());
            bulkUpdate.addThemeVariants(ButtonVariant.LUMO_ERROR);
            toolbar.add(bulkUpdate);
            updateBulkButton();
        }
        toolbar.add(countLabel);

        filterChips.getStyle().set("gap", "var(--lumo-space-xs)").set("flex-wrap", "wrap");

        configureGrid();

        VerticalLayout layout = new VerticalLayout(toolbar, filterChips, grid);
        layout.setPadding(false);
        layout.setSizeFull();
        layout.expand(grid);
        return layout;
    }

    private void configureGrid() {
        grid.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES, GridVariant.LUMO_COLUMN_BORDERS);
        grid.setSizeFull();
        grid.setMultiSort(true);
        grid.setPageSize(50);
        grid.setColumnReorderingAllowed(true);

        for (int i = 0; i < table.columns().size(); i++) {
            final int idx = i;
            ColumnInfo c = table.columns().get(i);
            Span head = new Span((c.primaryKey() ? "🔑 " : "") + c.name());
            head.getElement().setAttribute("title", c.dataType() + (c.notNull() ? " NOT NULL" : "")
                    + (c.comment() != null ? "\n" + c.comment() : ""));
            grid.addColumn(r -> cell(r.get(idx)))
                    .setHeader(head)
                    .setKey(c.name())
                    .setSortProperty(c.name())
                    .setResizable(true)
                    .setAutoWidth(false)
                    .setWidth(width(c))
                    .setFlexGrow(0);
        }

        grid.setItems(query -> {
            List<Sort> sorts = query.getSortOrders().stream()
                    .map(o -> new Sort(o.getSorted(), o.getDirection() == SortDirection.ASCENDING))
                    .toList();
            lastSorts = sorts;
            int offset = query.getOffset();
            int limit = query.getLimit();
            try {
                return data.fetch(table, filters, sorts, offset, limit).stream();
            } catch (Exception e) {
                Ui.error(e);
                return Stream.empty();
            }
        });

        grid.addItemDoubleClickListener(e -> new RowDetailsDialog(table, e.getItem(),
                CurrentUser.hasRole(Roles.OPERATOR) && metadata.isInsertAllowed(table.info()),
                this::openInsert,
                editable.isEmpty() ? null : row -> new UpdateDialog(table, editable, row, updates, this::refresh).open())
                .open());
    }

    private static String cell(String v) {
        if (v == null) return "∅";
        String s = v.replace('\n', ' ');
        return s.length() > CELL_PREVIEW ? s.substring(0, CELL_PREVIEW) + "…" : s;
    }

    private static String width(ColumnInfo c) {
        if (c.isBoolean()) return "90px";
        if (c.isNumeric()) return "130px";
        if (c.isDateTime()) return "210px";
        if (c.isUuid()) return "300px";
        if (c.isLongText()) return "320px";
        return "180px";
    }

    private void onFiltersChanged() {
        filterChips.removeAll();
        for (Filter f : new ArrayList<>(filters)) {
            Button chip = new Button(f.toString(), VaadinIcon.CLOSE_SMALL.create(), e -> {
                filters.remove(f);
                onFiltersChanged();
            });
            chip.setIconAfterText(true);
            chip.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_CONTRAST);
            filterChips.add(chip);
        }
        if (!filters.isEmpty()) {
            Button clear = new Button("Сбросить все", e -> {
                filters.clear();
                onFiltersChanged();
            });
            clear.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            filterChips.add(clear);
            audit.record(AuditAction.DB_VIEW, table.ref().qualified(), null, true,
                    Map.of("filters", filters.toString()));
        }
        countLabel.setText("");
        updateBulkButton();
        grid.getDataProvider().refreshAll();
    }

    private void updateBulkButton() {
        if (bulkUpdate == null) return;
        bulkUpdate.setEnabled(!filters.isEmpty());
        bulkUpdate.setTooltipText(filters.isEmpty()
                ? "Сначала задайте фильтр — изменение всей таблицы запрещено"
                : "Изменить колонки во всех строках под фильтром (до " + updates.maxRows() + " строк)");
    }

    private void refresh() {
        countLabel.setText("");
        grid.getDataProvider().refreshAll();
    }

    private Component exportLink() {
        String user = CurrentUser.name();
        String fileName = table.ref().qualified() + "_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        StreamResource resource = new StreamResource(fileName, () -> {
            // runs at download time, with the filters/sorting at the moment of the click
            byte[] csv = data.exportCsv(user, table, List.copyOf(filters), lastSorts);
            return new ByteArrayInputStream(csv);
        });
        resource.setContentType("text/csv; charset=utf-8");
        Anchor link = new Anchor(resource, "");
        link.getElement().setAttribute("download", true);
        Button btn = new Button("CSV (до " + data.maxExportRows() + ")", VaadinIcon.DOWNLOAD.create());
        btn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        link.add(btn);
        return link;
    }

    private void openInsert(Row template) {
        new InsertDialog(table, template, data, inserted -> grid.getDataProvider().refreshAll()).open();
    }
}
