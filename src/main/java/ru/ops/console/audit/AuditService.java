package ru.ops.console.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Service;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.config.SafeLog;
import ru.ops.console.security.CurrentUser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Audit log. Every event is written:
 * <ol>
 *   <li>to a separate {@code AUDIT} log (file logs/audit.log, one JSON line) — always;</li>
 *   <li>to a database table — if {@code console.audit.jdbc-enabled=true}.
 *       For database changes the audit record is written in the same transaction as the change itself.</li>
 * </ol>
 */
@Service
public class AuditService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final Pattern TABLE_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$");

    private final JdbcTemplate jdbc;
    /** Own connection pool when the audit log lives in a separate database. */
    private final HikariDataSource separateDataSource;
    private final ObjectMapper json;
    private final ConsoleProperties.Audit props;
    private volatile boolean jdbcReady;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json, ConsoleProperties props) {
        this.json = json;
        this.props = props.getAudit();
        if (this.props.isJdbcEnabled() && this.props.hasSeparateDatabase()) {
            HikariDataSource ds = new HikariDataSource();
            ds.setPoolName("audit");
            ds.setJdbcUrl(this.props.getJdbcUrl());
            ds.setUsername(this.props.getJdbcUser());
            ds.setPassword(this.props.getJdbcPassword());
            ds.setMaximumPoolSize(2);
            ds.setInitializationFailTimeout(-1);
            this.separateDataSource = ds;
            this.jdbc = new JdbcTemplate(ds);
        } else {
            this.separateDataSource = null;
            this.jdbc = jdbc;
        }
    }

    @PreDestroy
    void close() {
        if (separateDataSource != null) separateDataSource.close();
    }

    @PostConstruct
    void init() {
        if (!props.isJdbcEnabled()) {
            log.info("Аудит в БД выключен, пишем только в logs/audit.log");
            return;
        }
        if (!TABLE_NAME.matcher(props.getJdbcTable()).matches()) {
            throw new IllegalStateException("Недопустимое имя таблицы аудита: " + props.getJdbcTable());
        }
        if (props.isCreateTable()) {
            try {
                String table = props.getJdbcTable();
                if (table.contains(".")) {
                    // "create schema if not exists" requires the CREATE privilege on the database even if the schema exists,
                    // and the console's account usually lacks it — so check for the schema first
                    String schema = table.substring(0, table.indexOf('.'));
                    Boolean exists = jdbc.queryForObject(
                            "select exists (select 1 from pg_namespace where nspname = ?)", Boolean.class, schema);
                    if (!Boolean.TRUE.equals(exists)) {
                        jdbc.execute("create schema " + schema);
                    }
                }
                jdbc.execute("""
                        create table if not exists %s (
                            id        bigserial primary key,
                            ts        timestamptz not null default now(),
                            username  text        not null,
                            action    text        not null,
                            target    text,
                            reason    text,
                            success   boolean     not null,
                            details   jsonb
                        )""".formatted(table));
                jdbc.execute("create index if not exists " + indexName(table) + " on " + table + " (ts desc)");
            } catch (Exception e) {
                log.error("Не удалось создать таблицу аудита {} — аудит в БД отключён: {}", props.getJdbcTable(), SafeLog.describe(e));
                return;
            }
        }
        jdbcReady = true;
    }

    private static String indexName(String table) {
        String t = table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
        return t + "_ts_idx";
    }

    public boolean isJdbcEnabled() {
        return jdbcReady;
    }

    // ------------------------------------------------------------------ writing

    /** Event on behalf of the current user (call from the UI thread). */
    public void record(AuditAction action, String target, String reason, boolean success, Map<String, ?> details) {
        record(CurrentUser.name(), action, target, reason, success, details);
    }

    /** Event on behalf of an explicitly given user (for background tasks). */
    public void record(String user, AuditAction action, String target, String reason, boolean success,
                       Map<String, ?> details) {
        AuditEvent ev = new AuditEvent(null, Instant.now(), user, action, target, reason, success, toJson(details));
        writeLog(ev);
        if (jdbcReady) {
            try {
                jdbc.update(insertSql(), ev.username(), ev.action().name(), ev.target(), ev.reason(), ev.success(),
                        ev.details());
            } catch (Exception e) {
                log.error("Не удалось записать аудит в БД: {}", SafeLog.describe(e));
            }
        }
    }

    /**
     * Writes an audit record inside someone else's transaction (the connection is neither closed nor committed).
     * If the write fails, an exception is thrown so that the main operation is rolled back too.
     */
    public void recordInTransaction(Connection conn, String user, AuditAction action, String target, String reason,
                                    Map<String, ?> details) throws SQLException {
        AuditEvent ev = new AuditEvent(null, Instant.now(), user, action, target, reason, true, toJson(details));
        if (jdbcReady && separateDataSource != null) {
            // Separate audit database: the record is written before the caller commits; if it fails, the
            // caller's change is rolled back, so there is still no change without an audit record.
            jdbc.update(insertSql(), ev.username(), ev.action().name(), ev.target(), ev.reason(), true, ev.details());
        } else if (jdbcReady) {
            try (PreparedStatement ps = conn.prepareStatement(insertSql())) {
                ps.setString(1, ev.username());
                ps.setString(2, ev.action().name());
                ps.setString(3, ev.target());
                ps.setString(4, ev.reason());
                ps.setBoolean(5, true);
                ps.setString(6, ev.details());
                ps.executeUpdate();
            }
        }
        writeLog(ev);
    }

    private String insertSql() {
        return "insert into " + props.getJdbcTable()
                + " (username, action, target, reason, success, details) values (?, ?, ?, ?, ?, cast(? as jsonb))";
    }

    private void writeLog(AuditEvent ev) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", ev.ts().toString());
        line.put("user", ev.username());
        line.put("action", ev.action().name());
        line.put("target", ev.target());
        line.put("reason", ev.reason());
        line.put("success", ev.success());
        line.put("details", ev.details());
        auditLog.info(toJson(line));
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) return null;
        try {
            return json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ------------------------------------------------------------------ reading

    public List<AuditEvent> search(String user, AuditAction action, String target, int limit) {
        if (!jdbcReady) return List.of();
        StringBuilder sql = new StringBuilder(
                "select id, ts, username, action, target, reason, success, details::text from "
                        + props.getJdbcTable() + " where 1=1");
        List<Object> args = new ArrayList<>();
        if (user != null && !user.isBlank()) {
            sql.append(" and username ilike ?");
            args.add("%" + user.trim() + "%");
        }
        if (action != null) {
            sql.append(" and action = ?");
            args.add(action.name());
        }
        if (target != null && !target.isBlank()) {
            sql.append(" and target ilike ?");
            args.add("%" + target.trim() + "%");
        }
        sql.append(" order by ts desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> {
            Timestamp ts = rs.getTimestamp(2);
            AuditAction a;
            try {
                a = AuditAction.valueOf(rs.getString(4));
            } catch (IllegalArgumentException e) {
                a = null;
            }
            return new AuditEvent(rs.getLong(1), ts == null ? null : ts.toInstant(), rs.getString(3), a,
                    rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getString(8));
        }, args.toArray());
    }

    // ----------------------------------------------------------- login events

    @EventListener
    public void onLogin(AuthenticationSuccessEvent event) {
        record(event.getAuthentication().getName(), AuditAction.LOGIN, null, null, true,
                Map.of("roles", event.getAuthentication().getAuthorities().toString()));
    }

    @EventListener
    public void onLoginFailure(AbstractAuthenticationFailureEvent event) {
        record(String.valueOf(event.getAuthentication().getName()), AuditAction.LOGIN_FAILED, null, null, false,
                Map.of("error", String.valueOf(event.getException().getMessage())));
    }

    @EventListener
    public void onLogout(LogoutSuccessEvent event) {
        record(event.getAuthentication().getName(), AuditAction.LOGOUT, null, null, true, null);
    }
}
