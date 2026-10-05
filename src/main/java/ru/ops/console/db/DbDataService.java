package ru.ops.console.db;

import org.springframework.stereotype.Service;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.config.ConcurrencyLimit;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.Filter;
import ru.ops.console.db.DbModel.InsertResult;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.Sort;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads table data (paged, with filters and sorting), exports CSV and inserts rows.
 * <p>
 * Values are always passed as parameters of type {@link Types#OTHER} — PostgreSQL infers
 * the parameter type from the column (jsonb, uuid, enum, timestamptz, numeric ...), no manual CASTs.
 * Column names come only from catalog metadata.
 */
@Service
public class DbDataService {

    private final ReadOnlyJdbc readOnly;
    private final DataSource dataSource;
    private final DbMetadataService metadata;
    private final AuditService audit;
    private final ConsoleProperties.Db props;
    private final ConcurrencyLimit exportLimit;

    public DbDataService(ReadOnlyJdbc readOnly, DataSource dataSource, DbMetadataService metadata,
                         AuditService audit, ConsoleProperties props) {
        this.readOnly = readOnly;
        this.dataSource = dataSource;
        this.metadata = metadata;
        this.audit = audit;
        this.props = props.getDb();
        this.exportLimit = new ConcurrencyLimit("выгрузка CSV", props.getLimits().getMaxParallelExports(),
                props.getLimits().getWaitMs());
    }

    // ------------------------------------------------------------------ reading

    public List<Row> fetch(TableDetails t, List<Filter> filters, List<Sort> sorts, int offset, int limit) {
        return fetch(CurrentUser.name(), t, filters, sorts, offset, limit);
    }

    public List<Row> fetch(String user, TableDetails t, List<Filter> filters, List<Sort> sorts, int offset, int limit) {
        List<Object> params = new ArrayList<>();
        String sql = selectSql(t, filters, sorts, params) + " limit ? offset ?";
        params.add(limit);
        params.add(offset);
        return readOnly.read(user, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(readOnly.timeoutSeconds());
                ps.setFetchSize(Math.min(limit, 1000));
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Row> rows = new ArrayList<>();
                    int n = t.columns().size();
                    while (rs.next()) {
                        List<String> values = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) values.add(rs.getString(i));
                        rows.add(new Row(values));
                    }
                    return rows;
                }
            }
        });
    }

    /** Exact row count for the filter (may be slow on large tables — run on demand). */
    public long count(TableDetails t, List<Filter> filters) {
        List<Object> params = new ArrayList<>();
        String sql = "select count(*) from " + t.ref().sql() + where(t, filters, params);
        return readOnly.read(CurrentUser.name(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(readOnly.timeoutSeconds());
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** Query text shown to the user (what exactly is executed). */
    public String describeQuery(TableDetails t, List<Filter> filters, List<Sort> sorts) {
        List<Object> params = new ArrayList<>();
        return inline(selectSql(t, filters, sorts, params), params);
    }

    /** Substitutes parameters as literals — for display to the user and for the audit log only. */
    static String inline(String sql, List<Object> params) {
        StringBuilder sb = new StringBuilder();
        int p = 0;
        for (char ch : sql.toCharArray()) {
            if (ch == '?' && p < params.size()) {
                Object v = params.get(p++);
                sb.append(v == null ? "NULL" : Sql.literal(String.valueOf(v)));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private String selectSql(TableDetails t, List<Filter> filters, List<Sort> sorts, List<Object> params) {
        String cols = t.columns().stream().map(c -> Sql.ident(c.name())).collect(Collectors.joining(", "));
        return "select " + cols + " from " + t.ref().sql() + where(t, filters, params) + orderBy(t, sorts);
    }

    /** WHERE clause for the UI filters; values are appended to params. Also used by updates by filter. */
    static String where(TableDetails t, List<Filter> filters, List<Object> params) {
        if (filters == null || filters.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        for (Filter f : filters) {
            ColumnInfo col = t.column(f.column());
            String c = Sql.ident(col.name());
            String v = f.value() == null ? "" : f.value();
            switch (f.op()) {
                case EQ -> { parts.add(c + " = ?"); params.add(v); }
                case NE -> { parts.add(c + " <> ?"); params.add(v); }
                case GT -> { parts.add(c + " > ?"); params.add(v); }
                case GE -> { parts.add(c + " >= ?"); params.add(v); }
                case LT -> { parts.add(c + " < ?"); params.add(v); }
                case LE -> { parts.add(c + " <= ?"); params.add(v); }
                case CONTAINS -> {
                    parts.add(c + "::text ilike ?");
                    params.add(new TextParam("%" + Sql.likeEscape(v) + "%"));
                }
                case STARTS_WITH -> {
                    parts.add(c + "::text like ?");
                    params.add(new TextParam(Sql.likeEscape(v) + "%"));
                }
                case IN -> {
                    List<String> items = List.of(v.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
                    if (items.isEmpty()) throw new DbException("Пустой список для фильтра IN по " + col.name());
                    parts.add(c + " in (" + items.stream().map(x -> "?").collect(Collectors.joining(", ")) + ")");
                    params.addAll(items);
                }
                case IS_NULL -> parts.add(c + " is null");
                case NOT_NULL -> parts.add(c + " is not null");
            }
        }
        return " where " + String.join(" and ", parts);
    }

    private String orderBy(TableDetails t, List<Sort> sorts) {
        List<String> parts = new ArrayList<>();
        if (sorts != null) {
            for (Sort s : sorts) {
                ColumnInfo col = t.column(s.column());
                parts.add(Sql.ident(col.name()) + (s.ascending() ? " asc" : " desc"));
            }
        }
        if (parts.isEmpty()) {
            // By default — newest records first (by primary key)
            for (String pk : t.primaryKey()) parts.add(Sql.ident(pk) + " desc");
        }
        return parts.isEmpty() ? "" : " order by " + String.join(", ", parts);
    }

    /** A parameter that must be passed strictly as text (for LIKE on ::text). */
    record TextParam(String value) {
        @Override
        public String toString() {
            return value;
        }
    }

    static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else if (p instanceof TextParam tp) {
                ps.setString(i + 1, tp.value());
            } else if (p == null) {
                ps.setNull(i + 1, Types.OTHER);
            } else {
                // the server infers the type from the column
                ps.setObject(i + 1, p.toString(), Types.OTHER);
            }
        }
    }

    // ------------------------------------------------------------------ CSV

    public byte[] exportCsv(String user, TableDetails t, List<Filter> filters, List<Sort> sorts) {
        return exportLimit.run(() -> doExportCsv(user, t, filters, sorts));
    }

    private byte[] doExportCsv(String user, TableDetails t, List<Filter> filters, List<Sort> sorts) {
        List<Row> rows = fetch(user, t, filters, sorts, 0, props.getMaxExportRows());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder sb = new StringBuilder("﻿"); // BOM so that Excel recognizes UTF-8
        sb.append(t.columns().stream().map(c -> csv(c.name())).collect(Collectors.joining(";"))).append("\r\n");
        for (Row r : rows) {
            sb.append(r.values().stream().map(DbDataService::csv).collect(Collectors.joining(";"))).append("\r\n");
        }
        out.writeBytes(sb.toString().getBytes(StandardCharsets.UTF_8));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rows", rows.size());
        details.put("filters", String.valueOf(filters));
        audit.record(user, AuditAction.DB_EXPORT, t.ref().qualified(), null, true, details);
        return out.toByteArray();
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    public int maxExportRows() {
        return props.getMaxExportRows();
    }

    // ------------------------------------------------------------------ insert

    /** INSERT text for the preview (values substituted as literals). */
    public String previewInsert(TableDetails t, List<ColumnValue> values) {
        List<ColumnValue> used = effective(t, values);
        if (used.isEmpty()) {
            return "insert into " + t.ref().sql() + " default values returning *;";
        }
        return "insert into " + t.ref().sql() + " ("
                + used.stream().map(v -> Sql.ident(v.column())).collect(Collectors.joining(", "))
                + ")\nvalues ("
                + used.stream().map(v -> v.mode() == ValueMode.NULL ? "NULL" : Sql.literal(v.value()))
                .collect(Collectors.joining(", "))
                + ")\nreturning *;";
    }

    public InsertResult insert(TableDetails stale, List<ColumnValue> values, String reason) {
        CurrentUser.require(Roles.OPERATOR);
        readOnly.requireEnabled();
        String user = CurrentUser.name();
        // Re-read metadata: work with the current structure and privileges
        TableDetails t = metadata.describe(stale.ref().schema(), stale.ref().name());
        if (!metadata.isInsertAllowed(t.info())) {
            throw new DbException("Вставка в " + t.ref() + " запрещена настройками консоли или правами БД");
        }
        if (props.getInsert().isRequireReason() && (reason == null || reason.isBlank())) {
            throw new DbException("Укажите обоснование (номер заявки/инцидента)");
        }
        List<ColumnValue> used = effective(t, values);
        String preview = previewInsert(t, values);

        String sql;
        if (used.isEmpty()) {
            sql = "insert into " + t.ref().sql() + " default values returning *";
        } else {
            sql = "insert into " + t.ref().sql() + " ("
                    + used.stream().map(v -> Sql.ident(v.column())).collect(Collectors.joining(", "))
                    + ") values ("
                    + used.stream().map(v -> "?").collect(Collectors.joining(", "))
                    + ") returning *";
        }

        try {
            return insertInTransaction(t, used, sql, preview, user, reason);
        } catch (SQLException | RuntimeException e) {
            // The connection is already back in the pool — auditing the failure does not wait for a second connection
            audit.record(user, AuditAction.DB_INSERT, t.ref().qualified(), reason, false,
                    Map.of("sql", preview, "error", String.valueOf(e.getMessage())));
            if (e instanceof SQLException se) throw new DbException(se);
            throw (RuntimeException) e;
        }
    }

    private InsertResult insertInTransaction(TableDetails t, List<ColumnValue> used, String sql, String preview,
                                             String user, String reason) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ReadOnlyJdbc.applicationName(c, user);
                Row inserted;
                List<String> columns = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout(readOnly.timeoutSeconds());
                    int i = 1;
                    for (ColumnValue v : used) {
                        if (v.mode() == ValueMode.NULL) ps.setNull(i++, Types.OTHER);
                        else ps.setObject(i++, v.value(), Types.OTHER);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        int n = rs.getMetaData().getColumnCount();
                        List<String> vals = new ArrayList<>(n);
                        for (int k = 1; k <= n; k++) {
                            columns.add(rs.getMetaData().getColumnName(k));
                            vals.add(rs.getString(k));
                        }
                        inserted = new Row(vals);
                    }
                }
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("sql", preview);
                Map<String, String> row = new LinkedHashMap<>();
                for (int k = 0; k < columns.size(); k++) row.put(columns.get(k), inserted.get(k));
                details.put("inserted", row);
                audit.recordInTransaction(c, user, AuditAction.DB_INSERT, t.ref().qualified(), reason, details);
                c.commit();
                return new InsertResult(columns, inserted, preview);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Drops columns in DEFAULT mode and validates generated columns. */
    private List<ColumnValue> effective(TableDetails t, List<ColumnValue> values) {
        List<ColumnValue> used = new ArrayList<>();
        for (ColumnValue v : values) {
            ColumnInfo col = t.column(v.column());
            if (v.mode() == ValueMode.DEFAULT) continue;
            if (col.isGeneratedAlways()) {
                throw new DbException("Колонка " + col.name() + " генерируется БД (GENERATED ALWAYS) — только DEFAULT");
            }
            if (v.mode() == ValueMode.NULL && col.notNull()) {
                throw new DbException("Колонка " + col.name() + " NOT NULL — NULL недопустим");
            }
            used.add(v);
        }
        return used;
    }
}
