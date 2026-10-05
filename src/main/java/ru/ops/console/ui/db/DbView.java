package ru.ops.console.ui.db;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.RolesAllowed;
import ru.ops.console.config.ConsoleProperties.Feature;
import ru.ops.console.config.RequiresFeature;
import ru.ops.console.audit.AuditService;
import ru.ops.console.db.DbDataService;
import ru.ops.console.db.DbUpdateService;
import ru.ops.console.db.DbMetadataService;
import ru.ops.console.db.DbModel.TableInfo;
import ru.ops.console.security.Roles;
import ru.ops.console.ui.MainLayout;
import ru.ops.console.ui.common.Ui;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Database tables. The address /db/{schema}/{table} can be shared with colleagues as a link.
 */
@RequiresFeature(Feature.DB)
@Route(value = "db/:schema?/:table?", layout = MainLayout.class)
@PageTitle("Таблицы БД")
@RolesAllowed(Roles.VIEWER)
public class DbView extends SplitLayout implements BeforeEnterObserver {

    private final DbMetadataService metadata;
    private final DbDataService data;
    private final AuditService audit;
    private final DbUpdateService updates;

    private final ComboBox<String> schemaSelect = new ComboBox<>("Схема");
    private final TextField tableFilter = new TextField();
    private final Grid<TableInfo> tables = new Grid<>();
    private final Div content = new Div();

    private List<TableInfo> allTables = List.of();
    private String currentSchema;
    private String currentTable;
    private boolean updatingFromRoute;
    private boolean schemasLoaded;

    public DbView(DbMetadataService metadata, DbDataService data, AuditService audit, DbUpdateService updates) {
        this.updates = updates;
        this.metadata = metadata;
        this.data = data;
        this.audit = audit;
        setSizeFull();
        setSplitterPosition(22);

        schemaSelect.setWidthFull();
        schemaSelect.addValueChangeListener(e -> {
            if (!updatingFromRoute && e.isFromClient() && e.getValue() != null) {
                navigate(e.getValue(), null);
            }
        });

        tableFilter.setPlaceholder("Фильтр таблиц…");
        tableFilter.setPrefixComponent(VaadinIcon.SEARCH.create());
        tableFilter.setClearButtonVisible(true);
        tableFilter.setValueChangeMode(ValueChangeMode.LAZY);
        tableFilter.setWidthFull();
        tableFilter.addValueChangeListener(e -> applyTableFilter());

        tables.addColumn(t -> t.ref().name()).setHeader("Таблица").setFlexGrow(1).setSortable(true);
        tables.addColumn(t -> t.kind() == ru.ops.console.db.DbModel.TableKind.TABLE ? "" : t.kind().label())
                .setHeader("").setAutoWidth(true).setFlexGrow(0);
        tables.addColumn(t -> approx(t.estimatedRows())).setHeader("≈ строк").setAutoWidth(true).setFlexGrow(0)
                .setTextAlign(com.vaadin.flow.component.grid.ColumnTextAlign.END);
        tables.setTooltipGenerator(t -> t.comment());
        tables.addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_ROW_STRIPES);
        tables.setSizeFull();
        tables.addItemClickListener(e -> navigate(e.getItem().ref().schema(), e.getItem().ref().name()));

        VerticalLayout left = new VerticalLayout(schemaSelect, tableFilter, tables);
        left.setSizeFull();
        left.setPadding(true);
        left.setSpacing(false);
        left.expand(tables);

        content.setSizeFull();
        showPlaceholder();

        addToPrimary(left);
        addToSecondary(content);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String schema = event.getRouteParameters().get("schema").orElse(null);
        String table = event.getRouteParameters().get("table").orElse(null);

        try {
            if (!schemasLoaded) {
                List<String> schemas = metadata.schemas();
                schemaSelect.setItems(schemas);
                schemasLoaded = true;
                if (schema == null && !schemas.isEmpty()) {
                    schema = schemas.contains("public") ? "public" : schemas.get(0);
                }
            }
            updatingFromRoute = true;
            if (schema != null && !Objects.equals(schema, currentSchema)) {
                schemaSelect.setValue(schema);
                allTables = metadata.tables(schema);
                currentSchema = schema;
                applyTableFilter();
            }
            if (table != null && !Objects.equals(table, currentTable)) {
                openTable(schema, table);
            } else if (table == null) {
                currentTable = null;
                showPlaceholder();
            }
        } catch (Exception e) {
            Ui.error(e);
        } finally {
            updatingFromRoute = false;
        }
    }

    private void navigate(String schema, String table) {
        Map<String, String> params = new HashMap<>();
        params.put("schema", schema);
        if (table != null) params.put("table", table);
        UI.getCurrent().navigate(DbView.class, new RouteParameters(params));
    }

    private void openTable(String schema, String table) {
        currentTable = table;
        content.removeAll();
        TablePanel panel = new TablePanel(metadata.describe(schema, table), metadata, data, updates, audit);
        content.add(panel);
        allTables.stream().filter(t -> t.ref().name().equals(table)).findFirst().ifPresent(tables::select);
    }

    private void applyTableFilter() {
        String f = tableFilter.getValue() == null ? "" : tableFilter.getValue().trim().toLowerCase(Locale.ROOT);
        tables.setItems(f.isEmpty() ? allTables
                : allTables.stream().filter(t -> t.ref().name().toLowerCase(Locale.ROOT).contains(f)).toList());
    }

    private void showPlaceholder() {
        content.removeAll();
        Span hint = new Span("Выберите таблицу слева");
        hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("padding", "var(--lumo-space-l)")
                .set("display", "block");
        content.add(hint);
    }

    static String approx(long n) {
        if (n >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1f млрд", n / 1e9);
        if (n >= 1_000_000L) return String.format(Locale.ROOT, "%.1f млн", n / 1e6);
        if (n >= 10_000L) return String.format(Locale.ROOT, "%.0f тыс", n / 1e3);
        return String.valueOf(n);
    }
}
