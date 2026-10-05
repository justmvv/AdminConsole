package ru.ops.console.db;

import org.springframework.stereotype.Service;
import ru.ops.console.audit.AuditAction;
import ru.ops.console.audit.AuditService;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.db.DbModel.BulkPreview;
import ru.ops.console.db.DbModel.ColumnChange;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.Filter;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.UpdateResult;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.security.CurrentUser;
import ru.ops.console.security.Roles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Changes data without user-written SQL: one row by primary key or a set of rows by a UI filter.
 * <p>
 * Only columns from {@link DbMetadataService#updatableColumns} change. Rows are locked with
 * {@code SELECT … FOR UPDATE} and {@code lock_timeout} so the application's work is never overwritten:
 * <ul>
 *   <li>single row — compared with the values the user saw before writing;</li>
 *   <li>by filter — the number of locked rows must match the one confirmed in the preview,
 *       and the UPDATE addresses the locked rows by their primary keys.</li>
 * </ul>
 * The audit record is written in the same transaction: if it fails, the change is rolled back.
 */
@Service
public class DbUpdateService {

    private static final int SAMPLE_ROWS = 20;
    /** PostgreSQL limit of bind parameters per statement. */
    private static final int MAX_BIND_PARAMS = 32_767;

    @FunctionalInterface
    private interface TxCallback<T> {
        T apply(Connection c) throws SQLException;
    }

    private final DataSource dataSource;
    private final ReadOnlyJdbc readOnly;
    private final DbMetadataService metadata;
    private final AuditService audit;
    private final ConsoleProperties.Update props;

    public DbUpdateService(DataSource dataSource, ReadOnlyJdbc readOnly, DbMetadataService metadata,
                           AuditService audit, ConsoleProperties props) {
        this.dataSource = dataSource;
        this.readOnly = readOnly;
        this.metadata = metadata;
        this.audit = audit;
        this.props = props.getDb().getUpdate();
    }

    public boolean isReasonRequired() {
        return props.isRequireReason();
    }

    public int maxRows() {
        return props.getMaxRows();
    }

    // --------------------------------------------------------------- single row

    /** Columns that actually change (value differs from the current one) — for the "before → after" view. */
    public List<ColumnChange> changes(TableDetails t, Row original, List<ColumnValue> values) {
        List<ColumnChange> result = new ArrayList<>();
        for (ColumnValue v : values) {
            int idx = t.indexOf(v.column());
            if (idx < 0) throw new DbException("Нет колонки " + v.column() + " в " + t.ref());
            if (v.mode() == ValueMode.DEFAULT) {
                throw new DbException("Колонка " + v.column() + ": при изменении допустимо только значение или NULL");
            }
            String old = original.get(idx);
            boolean isNull = v.mode() == ValueMode.NULL;
            String newValue = isNull ? null : Objects.requireNonNullElse(v.value(), "");
            if (!Objects.equals(old, newValue)) {
                result.add(new ColumnChange(v.column(), old, newValue, isNull));
            }
        }
        return result;
    }

    public String previewRow(TableDetails t, Row original, List<ColumnValue> values) {
        List<ColumnChange> changes = changes(t, original, values);
        List<Object> params = new ArrayList<>();
        String sql = rowUpdateSql(t, changes, original, params);
        return DbDataService.inline(sql, params) + ";";
    }

    public UpdateResult updateRow(TableDetails stale, Row original, List<ColumnValue> values, String reason) {
        CurrentUser.require(Roles.OPERATOR);
        String user = CurrentUser.name();
        TableDetails t = fresh(stale);
        List<ColumnChange> changes = changes(t, original, values);
        if (changes.isEmpty()) throw new DbException("Нет изменений");
        checkAllowed(t, changes.stream().map(c -> new ColumnValue(c.column(),
                c.newIsNull() ? ValueMode.NULL : ValueMode.VALUE, c.newValue())).toList());
        checkReason(reason);

        List<Object> params = new ArrayList<>();
        String sql = rowUpdateSql(t, changes, original, params) + " returning *";
        String preview = previewRow(t, original, values);
        String target = t.ref().qualified();

        try {
            return inTransaction(user, c -> {
                Row current = lockRow(c, t, original);
                if (current == null) {
                    throw new DbConflictException("Строка не найдена — её удалили или изменили ключ", null);
                }
                if (!current.equals(original)) {
                    throw new DbConflictException("Строка изменилась, пока вы её редактировали (колонки: "
                            + differingColumns(t, original, current) + "). Проверьте актуальные значения.", current);
                }
                List<String> columns = new ArrayList<>();
                Row updated;
                int count = 0;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout(readOnly.timeoutSeconds());
                    DbDataService.bind(ps, params);
                    try (ResultSet rs = ps.executeQuery()) {
                        int n = rs.getMetaData().getColumnCount();
                        for (int k = 1; k <= n; k++) columns.add(rs.getMetaData().getColumnName(k));
                        List<String> vals = new ArrayList<>(n);
                        while (rs.next()) {
                            count++;
                            vals.clear();
                            for (int k = 1; k <= n; k++) vals.add(rs.getString(k));
                        }
                        updated = new Row(new ArrayList<>(vals)); // List.copyOf does not allow NULL values
                    }
                }
                if (count != 1) throw new DbException("Ожидалось изменение одной строки, затронуто: " + count);

                Map<String, Object> details = new LinkedHashMap<>();
                details.put("sql", preview);
                details.put("key", key(t, original));
                details.put("changes", changes.stream().map(ch -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("column", ch.column());
                    m.put("old", ch.oldValue());
                    m.put("new", ch.shownNew());
                    return m;
                }).toList());
                audit.recordInTransaction(c, user, AuditAction.DB_UPDATE, target, reason, details);
                return new UpdateResult(1, columns, updated, preview);
            });
        } catch (SQLException | RuntimeException e) {
            audit.record(user, AuditAction.DB_UPDATE, target, reason, false,
                    Map.of("sql", preview, "error", String.valueOf(e.getMessage())));
            throw e instanceof SQLException se ? new DbException(se) : (RuntimeException) e;
        }
    }

    private String rowUpdateSql(TableDetails t, List<ColumnChange> changes, Row original, List<Object> params) {
        List<String> set = new ArrayList<>();
        for (ColumnChange ch : changes) {
            set.add(Sql.ident(t.column(ch.column()).name()) + " = ?");
            params.add(ch.shownNew());
        }
        return "update " + t.ref().sql() + " set " + String.join(", ", set) + " where " + pkWhere(t, original, params);
    }

    private String pkWhere(TableDetails t, Row row, List<Object> params) {
        if (t.primaryKey().isEmpty()) throw new DbException("У таблицы " + t.ref() + " нет первичного ключа");
        List<String> parts = new ArrayList<>();
        for (String pk : t.primaryKey()) {
            String v = row.get(t.indexOf(pk));
            if (v == null) throw new DbException("Пустое значение ключа " + pk);
            parts.add(Sql.ident(pk) + " = ?");
            params.add(v);
        }
        return String.join(" and ", parts);
    }

    private Row lockRow(Connection c, TableDetails t, Row original) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "select " + allColumns(t) + " from " + t.ref().sql() + " where " + pkWhere(t, original, params)
                + " for update";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(readOnly.timeoutSeconds());
            DbDataService.bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                List<String> vals = new ArrayList<>();
                for (int k = 1; k <= t.columns().size(); k++) vals.add(rs.getString(k));
                return new Row(vals);
            }
        }
    }

    private static String differingColumns(TableDetails t, Row a, Row b) {
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < t.columns().size(); i++) {
            if (!Objects.equals(a.get(i), b.get(i))) cols.add(t.columns().get(i).name());
        }
        return String.join(", ", cols);
    }

    private static Map<String, String> key(TableDetails t, Row row) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String pk : t.primaryKey()) m.put(pk, row.get(t.indexOf(pk)));
        return m;
    }

    // ---------------------------------------------------------------- by filter

    /** How many rows the update will affect and what they look like now (read only, no locks). */
    public BulkPreview previewBulk(TableDetails t, List<Filter> filters, List<ColumnValue> values) {
        checkBulkInput(t, filters, values);
        List<Object> countParams = new ArrayList<>();
        String countSql = "select count(*) from " + t.ref().sql() + DbDataService.where(t, filters, countParams);
        List<Object> sampleParams = new ArrayList<>();
        List<String> sampleColumns = sampleColumns(t, values);
        String sampleSql = "select " + sampleColumns.stream().map(Sql::ident).collect(Collectors.joining(", "))
                + " from " + t.ref().sql() + DbDataService.where(t, filters, sampleParams)
                + " order by " + pkOrder(t) + " limit " + SAMPLE_ROWS;

        return readOnly.read(CurrentUser.name(), c -> {
            long count;
            try (PreparedStatement ps = c.prepareStatement(countSql)) {
                ps.setQueryTimeout(readOnly.timeoutSeconds());
                DbDataService.bind(ps, countParams);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    count = rs.getLong(1);
                }
            }
            List<Row> sample = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sampleSql)) {
                ps.setQueryTimeout(readOnly.timeoutSeconds());
                DbDataService.bind(ps, sampleParams);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<String> vals = new ArrayList<>();
                        for (int k = 1; k <= sampleColumns.size(); k++) vals.add(rs.getString(k));
                        sample.add(new Row(vals));
                    }
                }
            }
            return new BulkPreview(count, sampleColumns, sample, bulkPreviewSql(t, filters, values));
        });
    }

    /**
     * @param expectedCount number of rows the user saw in the preview and confirmed
     */
    public UpdateResult updateBulk(TableDetails stale, List<Filter> filters, List<ColumnValue> values,
                                   long expectedCount, String reason) {
        CurrentUser.require(Roles.OPERATOR);
        String user = CurrentUser.name();
        TableDetails t = fresh(stale);
        checkBulkInput(t, filters, values);
        checkReason(reason);
        if (expectedCount <= 0) throw new DbException("Под фильтр не попадает ни одна строка");
        if (expectedCount > props.getMaxRows()) {
            throw new DbException("Под фильтр попадает " + expectedCount + " строк — больше лимита "
                    + props.getMaxRows() + " (console.db.update.max-rows). Уточните фильтр.");
        }
        if (expectedCount * t.primaryKey().size() + values.size() > MAX_BIND_PARAMS) {
            throw new DbException("Слишком много строк для одного изменения — уточните фильтр");
        }

        String preview = bulkPreviewSql(t, filters, values);
        String target = t.ref().qualified();
        List<String> lockColumns = sampleColumns(t, values);

        try {
            return inTransaction(user, c -> {
                // 1. Lock the rows under the filter (limit one above the expected count — to detect growth)
                List<Object> lockParams = new ArrayList<>();
                String lockSql = "select " + lockColumns.stream().map(Sql::ident).collect(Collectors.joining(", "))
                        + " from " + t.ref().sql() + DbDataService.where(t, filters, lockParams)
                        + " order by " + pkOrder(t) + " limit " + (expectedCount + 1) + " for update";
                List<Row> locked = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(lockSql)) {
                    ps.setQueryTimeout(readOnly.timeoutSeconds());
                    DbDataService.bind(ps, lockParams);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            List<String> vals = new ArrayList<>();
                            for (int k = 1; k <= lockColumns.size(); k++) vals.add(rs.getString(k));
                            locked.add(new Row(vals));
                        }
                    }
                }
                if (locked.size() != expectedCount) {
                    throw new DbConflictException("Под фильтр сейчас попадает "
                            + (locked.size() > expectedCount ? "больше " + expectedCount : locked.size())
                            + " строк, а подтверждено " + expectedCount + ". Данные изменились — проверьте заново.",
                            null);
                }

                // 2. Change exactly the locked rows — by their primary keys
                int pkCount = t.primaryKey().size();
                List<Object> params = new ArrayList<>();
                List<String> set = new ArrayList<>();
                for (ColumnValue v : values) {
                    set.add(Sql.ident(t.column(v.column()).name()) + " = ?");
                    params.add(v.mode() == ValueMode.NULL ? null : Objects.requireNonNullElse(v.value(), ""));
                }
                String tuple = "(" + String.join(", ", java.util.Collections.nCopies(pkCount, "?")) + ")";
                for (Row r : locked) {
                    for (int k = 0; k < pkCount; k++) params.add(r.get(k));
                }
                String sql = "update " + t.ref().sql() + " set " + String.join(", ", set)
                        + " where (" + t.primaryKey().stream().map(Sql::ident).collect(Collectors.joining(", "))
                        + ") in (" + String.join(", ", java.util.Collections.nCopies(locked.size(), tuple)) + ")";
                int updated;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout(readOnly.timeoutSeconds());
                    DbDataService.bind(ps, params);
                    updated = ps.executeUpdate();
                }
                if (updated != locked.size()) {
                    throw new DbException("Ожидалось изменение " + locked.size() + " строк, затронуто: " + updated);
                }

                // 3. Audit: keys and previous values of every row
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("sql", preview);
                details.put("filters", filters.stream().map(Filter::toString).toList());
                details.put("rows", updated);
                Map<String, String> newValues = new LinkedHashMap<>();
                values.forEach(v -> newValues.put(v.column(), v.mode() == ValueMode.NULL ? null : v.value()));
                details.put("set", newValues);
                List<Map<String, String>> before = new ArrayList<>();
                for (Row r : locked) {
                    Map<String, String> m = new LinkedHashMap<>();
                    for (int k = 0; k < lockColumns.size(); k++) m.put(lockColumns.get(k), r.get(k));
                    before.add(m);
                }
                details.put("before", before);
                audit.recordInTransaction(c, user, AuditAction.DB_BULK_UPDATE, target, reason, details);
                return new UpdateResult(updated, null, null, preview);
            });
        } catch (SQLException | RuntimeException e) {
            audit.record(user, AuditAction.DB_BULK_UPDATE, target, reason, false,
                    Map.of("sql", preview, "expectedRows", expectedCount, "error", String.valueOf(e.getMessage())));
            throw e instanceof SQLException se ? new DbException(se) : (RuntimeException) e;
        }
    }

    private void checkBulkInput(TableDetails t, List<Filter> filters, List<ColumnValue> values) {
        if (filters == null || filters.isEmpty()) {
            throw new DbException("Изменение без фильтра запрещено — задайте хотя бы один фильтр");
        }
        if (values == null || values.isEmpty()) throw new DbException("Выберите колонки для изменения");
        for (ColumnValue v : values) {
            if (v.mode() == ValueMode.DEFAULT) {
                throw new DbException("Колонка " + v.column() + ": при изменении допустимо только значение или NULL");
            }
        }
        checkAllowed(t, values);
    }

    /** Key + changed columns (no duplicates) — for the preview and the "before" snapshot in the audit log. */
    private static List<String> sampleColumns(TableDetails t, List<ColumnValue> values) {
        List<String> cols = new ArrayList<>(t.primaryKey());
        for (ColumnValue v : values) {
            if (!cols.contains(v.column())) cols.add(t.column(v.column()).name());
        }
        return cols;
    }

    private static String pkOrder(TableDetails t) {
        return t.primaryKey().stream().map(Sql::ident).collect(Collectors.joining(", "));
    }

    private String bulkPreviewSql(TableDetails t, List<Filter> filters, List<ColumnValue> values) {
        List<Object> params = new ArrayList<>();
        List<String> set = new ArrayList<>();
        for (ColumnValue v : values) {
            set.add(Sql.ident(t.column(v.column()).name()) + " = ?");
            params.add(v.mode() == ValueMode.NULL ? null : Objects.requireNonNullElse(v.value(), ""));
        }
        String sql = "update " + t.ref().sql() + " set " + String.join(", ", set)
                + DbDataService.where(t, filters, params);
        return DbDataService.inline(sql, params) + ";";
    }

    // ------------------------------------------------------------------ common

    /** Re-read metadata: work with the current structure and privileges. */
    private TableDetails fresh(TableDetails stale) {
        TableDetails t = metadata.describe(stale.ref().schema(), stale.ref().name());
        List<String> before = stale.columns().stream().map(ColumnInfo::name).toList();
        List<String> now = t.columns().stream().map(ColumnInfo::name).toList();
        if (!before.equals(now) || !stale.primaryKey().equals(t.primaryKey())) {
            throw new DbException("Структура таблицы " + t.ref() + " изменилась — откройте таблицу заново");
        }
        return t;
    }

    private void checkAllowed(TableDetails t, List<ColumnValue> values) {
        Set<String> allowed = metadata.updatableColumns(t).stream().map(ColumnInfo::name)
                .collect(Collectors.toSet());
        Set<String> seen = new java.util.HashSet<>();
        for (ColumnValue v : values) {
            ColumnInfo col = t.column(v.column());
            if (!allowed.contains(col.name())) {
                throw new DbException("Изменение колонки " + t.ref() + "." + col.name()
                        + " запрещено настройками консоли или правами БД");
            }
            if (!seen.add(col.name())) throw new DbException("Колонка " + col.name() + " указана дважды");
            if (v.mode() == ValueMode.NULL && col.notNull()) {
                throw new DbException("Колонка " + col.name() + " NOT NULL — NULL недопустим");
            }
        }
    }

    private void checkReason(String reason) {
        if (props.isRequireReason() && (reason == null || reason.isBlank())) {
            throw new DbException("Укажите обоснование (номер заявки/инцидента)");
        }
    }

    private <T> T inTransaction(String user, TxCallback<T> callback) throws SQLException {
        readOnly.requireEnabled();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                ReadOnlyJdbc.applicationName(c, user);
                try (PreparedStatement ps = c.prepareStatement("select set_config('lock_timeout', ?, true)")) {
                    ps.setString(1, props.getLockTimeoutSeconds() + "s");
                    ps.execute();
                }
                T result = callback.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static String allColumns(TableDetails t) {
        return t.columns().stream().map(c -> Sql.ident(c.name())).collect(Collectors.joining(", "));
    }
}
