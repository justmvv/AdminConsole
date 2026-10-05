package ru.ops.console.db;

import org.springframework.stereotype.Service;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.config.Globs;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ConstraintInfo;
import ru.ops.console.db.DbModel.IndexInfo;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.TableInfo;
import ru.ops.console.db.DbModel.TableKind;
import ru.ops.console.db.DbModel.TableRef;
import ru.ops.console.security.CurrentUser;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL metadata from pg_catalog. All schema/table names coming from the UI are
 * checked here against the catalog and the allowed schemas — only existing objects
 * ever reach SQL.
 */
@Service
public class DbMetadataService {

    private final ReadOnlyJdbc jdbc;
    private final ConsoleProperties.Db props;
    private final boolean readOnlyMode;

    public DbMetadataService(ReadOnlyJdbc jdbc, ConsoleProperties props) {
        this.jdbc = jdbc;
        this.props = props.getDb();
        this.readOnlyMode = props.isReadOnly();
    }

    /** Server version, database and user — for the overview page. */
    public Map<String, String> serverInfo() {
        return jdbc.read(CurrentUser.name(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "select version(), current_database(), current_user, inet_server_addr()::text, now()::text");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, String> m = new LinkedHashMap<>();
                m.put("Версия", rs.getString(1));
                m.put("База", rs.getString(2));
                m.put("Пользователь БД", rs.getString(3));
                m.put("Адрес сервера", rs.getString(4));
                m.put("Время сервера", rs.getString(5));
                return m;
            }
        });
    }

    public List<String> schemas() {
        return jdbc.read(CurrentUser.name(), c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    select nspname from pg_namespace
                    where nspname not like 'pg\\_%' and nspname <> 'information_schema'
                      and has_schema_privilege(oid, 'USAGE')
                    order by nspname""");
                 ResultSet rs = ps.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) {
                    String s = rs.getString(1);
                    if (isSchemaAllowed(s)) result.add(s);
                }
                return result;
            }
        });
    }

    public boolean isSchemaAllowed(String schema) {
        return props.getSchemas().isEmpty() || Globs.matchesAny(schema, props.getSchemas());
    }

    public boolean isInsertAllowed(TableInfo t) {
        ConsoleProperties.Insert ins = props.getInsert();
        return !readOnlyMode && ins.isEnabled()
                && t.kind().insertable()
                && t.canInsert()
                && Globs.matchesAny(t.ref().qualified(), ins.getAllowedTables());
    }

    /**
     * Columns the console allows to change: whitelist {@code console.db.update.allowed-columns}
     * + UPDATE privilege of the DB account. Primary key, identity and generated columns never change;
     * tables without a primary key are not editable (a row cannot be addressed unambiguously).
     */
    public List<ColumnInfo> updatableColumns(TableDetails t) {
        ConsoleProperties.Update up = props.getUpdate();
        if (readOnlyMode || !up.isEnabled() || !t.info().kind().insertable() || t.primaryKey().isEmpty()) return List.of();
        return t.columns().stream()
                .filter(c -> !c.primaryKey() && c.identity().isEmpty() && c.generated().isEmpty() && c.canUpdate())
                .filter(c -> Globs.matchesAny(t.ref().qualified() + "." + c.name(), up.getAllowedColumns()))
                .toList();
    }

    public List<TableInfo> tables(String schema) {
        requireSchema(schema);
        return jdbc.read(CurrentUser.name(), c -> {
            String sql = """
                    select c.relname, c.relkind::text, greatest(c.reltuples, 0)::bigint,
                           obj_description(c.oid, 'pg_class'),
                           has_table_privilege(c.oid, 'INSERT')
                    from pg_class c
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ?
                      and c.relkind in ('r', 'p', 'v', 'm', 'f')
                      and has_table_privilege(c.oid, 'SELECT')
                    """ + (props.isShowPartitions() ? "" : " and not c.relispartition ") + " order by c.relname";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TableInfo> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(new TableInfo(new TableRef(schema, rs.getString(1)),
                                TableKind.of(rs.getString(2)), rs.getLong(3), rs.getString(4), rs.getBoolean(5)));
                    }
                    return result;
                }
            }
        });
    }

    public TableDetails describe(String schema, String table) {
        requireSchema(schema);
        return jdbc.read(CurrentUser.name(), c -> {
            TableInfo info;
            try (PreparedStatement ps = c.prepareStatement("""
                    select c.relkind::text, greatest(c.reltuples, 0)::bigint, obj_description(c.oid, 'pg_class'),
                           has_table_privilege(c.oid, 'INSERT')
                    from pg_class c join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = ? and c.relname = ? and c.relkind in ('r', 'p', 'v', 'm', 'f')
                      and has_table_privilege(c.oid, 'SELECT')""")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new DbException("Таблица " + schema + "." + table + " не найдена или нет прав SELECT");
                    }
                    info = new TableInfo(new TableRef(schema, table), TableKind.of(rs.getString(1)), rs.getLong(2),
                            rs.getString(3), rs.getBoolean(4));
                }
            }

            List<String> pk = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select a.attname
                    from pg_index i
                    join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
                    where i.indrelid = format('%I.%I', ?::text, ?::text)::regclass and i.indisprimary
                    order by array_position(i.indkey::int2[], a.attnum)""")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) pk.add(rs.getString(1));
                }
            }

            List<ColumnInfo> columns = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select a.attnum, a.attname,
                           format_type(a.atttypid, a.atttypmod)  as data_type,
                           format_type(a.atttypid, null)         as cast_type,
                           coalesce(bt.typname, t.typname)       as type_name,
                           coalesce(bt.typcategory, t.typcategory)::text as category,
                           a.attnotnull,
                           pg_get_expr(d.adbin, d.adrelid)       as default_expr,
                           a.attidentity::text, a.attgenerated::text,
                           col_description(a.attrelid, a.attnum) as comment,
                           (select array_agg(e.enumlabel::text order by e.enumsortorder)
                              from pg_enum e where e.enumtypid = coalesce(bt.oid, t.oid)) as enum_values,
                           has_column_privilege(a.attrelid, a.attnum, 'UPDATE') as can_update
                    from pg_attribute a
                    join pg_type t on t.oid = a.atttypid
                    left join pg_type bt on t.typtype = 'd' and bt.oid = t.typbasetype
                    left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                    where a.attrelid = format('%I.%I', ?::text, ?::text)::regclass
                      and a.attnum > 0 and not a.attisdropped
                    order by a.attnum""")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        columns.add(new ColumnInfo(
                                rs.getInt(1), name, rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getBoolean(7), rs.getString(8),
                                flag(rs.getString(9)), flag(rs.getString(10)), rs.getString(11),
                                stringArray(rs.getArray(12)), pk.contains(name), rs.getBoolean(13)));
                    }
                }
            }

            List<ConstraintInfo> constraints = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    select conname,
                           case contype when 'p' then 'PRIMARY KEY' when 'f' then 'FOREIGN KEY'
                                        when 'u' then 'UNIQUE' when 'c' then 'CHECK' when 'x' then 'EXCLUDE'
                                        else contype::text end,
                           pg_get_constraintdef(oid)
                    from pg_constraint
                    where conrelid = format('%I.%I', ?::text, ?::text)::regclass
                    order by contype, conname""")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        constraints.add(new ConstraintInfo(rs.getString(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }

            List<IndexInfo> indexes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "select indexname, indexdef from pg_indexes where schemaname = ? and tablename = ? order by 1")) {
                ps.setString(1, schema);
                ps.setString(2, table);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) indexes.add(new IndexInfo(rs.getString(1), rs.getString(2)));
                }
            }
            return new TableDetails(info, columns, pk, constraints, indexes);
        });
    }

    private void requireSchema(String schema) {
        if (schema == null || !isSchemaAllowed(schema)) {
            throw new DbException("Схема " + schema + " недоступна в консоли");
        }
    }

    private static String flag(String v) {
        if (v == null) return "";
        String s = v.replace("\0", "").trim();
        return s;
    }

    private static List<String> stringArray(Array a) throws java.sql.SQLException {
        if (a == null) return List.of();
        Object arr = a.getArray();
        if (arr instanceof String[] s) return Arrays.asList(s);
        if (arr instanceof Object[] o) return Arrays.stream(o).map(String::valueOf).toList();
        return List.of();
    }
}
