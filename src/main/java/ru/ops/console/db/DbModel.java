package ru.ops.console.db;

import java.util.List;

/**
 * PostgreSQL metadata model as seen by the UI.
 */
public final class DbModel {

    private DbModel() {
    }

    public record TableRef(String schema, String name) {
        public String qualified() {
            return schema + "." + name;
        }

        /** Safely quoted name for SQL. */
        public String sql() {
            return Sql.ident(schema) + "." + Sql.ident(name);
        }

        @Override
        public String toString() {
            return qualified();
        }
    }

    public enum TableKind {
        TABLE("таблица"), PARTITIONED("секц. таблица"), VIEW("view"), MATVIEW("mat. view"), FOREIGN("foreign"), OTHER("?");

        private final String label;

        TableKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean insertable() {
            return this == TABLE || this == PARTITIONED;
        }

        static TableKind of(String relkind) {
            return switch (relkind) {
                case "r" -> TABLE;
                case "p" -> PARTITIONED;
                case "v" -> VIEW;
                case "m" -> MATVIEW;
                case "f" -> FOREIGN;
                default -> OTHER;
            };
        }
    }

    public record TableInfo(TableRef ref, TableKind kind, long estimatedRows, String comment, boolean canInsert) {
    }

    /**
     * @param dataType     type for display: numeric(19,2), character varying(64) ...
     * @param castType     type without modifier for CAST(? AS ...): numeric, character varying ...
     * @param category     pg_type.typcategory: N, S, B, D, U, E, A ...
     * @param identity     '' — none, 'a' — ALWAYS, 'd' — BY DEFAULT
     * @param generated    '' — none, 's' — STORED
     * @param enumValues   enum values (or empty)
     * @param canUpdate    the DB account has the UPDATE privilege on the column
     */
    public record ColumnInfo(
            int position,
            String name,
            String dataType,
            String castType,
            String typeName,
            String category,
            boolean notNull,
            String defaultExpr,
            String identity,
            String generated,
            String comment,
            List<String> enumValues,
            boolean primaryKey,
            boolean canUpdate) {

        public boolean isGeneratedAlways() {
            return "s".equals(generated) || "a".equals(identity);
        }

        public boolean hasDefault() {
            return defaultExpr != null || !identity.isEmpty() || !generated.isEmpty();
        }

        public boolean isJson() {
            return "json".equals(typeName) || "jsonb".equals(typeName);
        }

        public boolean isBoolean() {
            return "B".equals(category);
        }

        public boolean isNumeric() {
            return "N".equals(category);
        }

        public boolean isDateTime() {
            return "D".equals(category);
        }

        public boolean isEnum() {
            return enumValues != null && !enumValues.isEmpty();
        }

        public boolean isUuid() {
            return "uuid".equals(typeName);
        }

        public boolean isLongText() {
            return isJson() || "text".equals(typeName) || "xml".equals(typeName) || "A".equals(category);
        }
    }

    public record ConstraintInfo(String name, String type, String definition) {
    }

    public record IndexInfo(String name, String definition) {
    }

    public record TableDetails(
            TableInfo info,
            List<ColumnInfo> columns,
            List<String> primaryKey,
            List<ConstraintInfo> constraints,
            List<IndexInfo> indexes) {

        public TableRef ref() {
            return info.ref();
        }

        public ColumnInfo column(String name) {
            for (ColumnInfo c : columns) {
                if (c.name().equals(name)) return c;
            }
            throw new IllegalArgumentException("Нет колонки " + name + " в " + ref());
        }

        public int indexOf(String name) {
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).name().equals(name)) return i;
            }
            return -1;
        }
    }

    // ----------------------------------------------------------------- filters

    public enum FilterOp {
        EQ("="), NE("≠"), GT(">"), GE("≥"), LT("<"), LE("≤"),
        CONTAINS("содержит"), STARTS_WITH("начинается с"), IN("в списке (через ,)"),
        IS_NULL("пусто (NULL)"), NOT_NULL("не пусто");

        private final String label;

        FilterOp(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean needsValue() {
            return this != IS_NULL && this != NOT_NULL;
        }
    }

    public record Filter(String column, FilterOp op, String value) {
        @Override
        public String toString() {
            return column + " " + op.label() + (op.needsValue() ? " " + value : "");
        }
    }

    public record Sort(String column, boolean ascending) {
    }

    /** One result row: values in PostgreSQL text representation (null = NULL). */
    public record Row(List<String> values) {
        public String get(int i) {
            return values.get(i);
        }
    }

    // ------------------------------------------------------------------ insert

    public enum ValueMode { VALUE, NULL, DEFAULT }

    public record ColumnValue(String column, ValueMode mode, String value) {
    }

    public record InsertResult(List<String> columns, Row inserted, String sqlPreview) {
    }

    // ------------------------------------------------------------------ update

    /** Column change in an UPDATE: VALUE or NULL only. */
    public record ColumnChange(String column, String oldValue, String newValue, boolean newIsNull) {
        public String shownNew() {
            return newIsNull ? null : newValue;
        }
    }

    /** Preview of an update by filter: how many rows and what they look like now. */
    public record BulkPreview(long count, List<String> columns, List<Row> sample, String sql) {
    }

    public record UpdateResult(int rows, List<String> columns, Row updated, String sqlPreview) {
    }
}
