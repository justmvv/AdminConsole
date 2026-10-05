package ru.ops.console.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ops.console.audit.AuditTestSupport;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.db.DbModel.BulkPreview;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.Filter;
import ru.ops.console.db.DbModel.FilterOp;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.security.Roles;
import ru.ops.console.support.OrchestratorDb;
import ru.ops.console.support.TestUsers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DbUpdateService against a real PostgreSQL: column privileges, protection against races with the application,
 * locking, updates by filter and auditing.
 */
@Testcontainers
class DbUpdateServiceIT {

    private static HikariDataSource ds;
    private static JdbcTemplate superuser;
    private static DbMetadataService metadata;
    private static DbDataService data;
    private static DbUpdateService updates;

    @BeforeAll
    static void setUp() {
        var pg = OrchestratorDb.container();
        superuser = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        // to test a composite key on a partitioned table
        superuser.execute("grant update (payload) on orch.event_log to console");

        ConsoleProperties props = new ConsoleProperties();
        props.getDb().setSchemas(List.of("orch", "admin_console"));
        props.getDb().getUpdate().setAllowedColumns(List.of(
                "orch.retry_task.*", "orch.payment.status", "orch.payment.attributes",
                "orch.event_log.payload", "orch.payment_step.*"));
        props.getDb().getUpdate().setMaxRows(50);
        props.getDb().getUpdate().setLockTimeoutSeconds(1);

        ds = OrchestratorDb.consoleDataSource();
        ReadOnlyJdbc readOnly = new ReadOnlyJdbc(ds, props);
        metadata = new DbMetadataService(readOnly, props);
        var audit = AuditTestSupport.auditService(ds, props);
        data = new DbDataService(readOnly, ds, metadata, audit, props);
        updates = new DbUpdateService(ds, readOnly, metadata, audit, props);
    }

    @AfterAll
    static void tearDown() {
        ds.close();
    }

    @BeforeEach
    void login() {
        TestUsers.loginAs("operator", Roles.OPERATOR, Roles.VIEWER);
    }

    @AfterEach
    void logout() {
        TestUsers.logout();
    }

    private static Row paymentRow(TableDetails t, String externalId) {
        List<Row> rows = data.fetch(t, List.of(new Filter("external_id", FilterOp.EQ, externalId)), List.of(), 0, 1);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static ColumnValue value(String column, String v) {
        return new ColumnValue(column, ValueMode.VALUE, v);
    }

    private static Map<String, Object> lastAudit(String action) {
        return superuser.queryForMap("select username, target, reason, success, details::text as details "
                + "from admin_console.audit_log where action = ? order by id desc limit 1", action);
    }

    // ------------------------------------------------------- column privileges

    @Test
    void updatableColumnsNeedWhitelistAndDbGrant() {
        assertThat(metadata.updatableColumns(metadata.describe("orch", "retry_task")))
                .extracting(ColumnInfo::name).containsExactly("run_at", "reason");   // step_name: no GRANT
        assertThat(metadata.updatableColumns(metadata.describe("orch", "payment")))
                .extracting(ColumnInfo::name).containsExactly("status", "attributes"); // amount: not whitelisted
        assertThat(metadata.updatableColumns(metadata.describe("orch", "payment_step"))).isEmpty(); // no GRANT
        assertThat(metadata.updatableColumns(metadata.describe("orch", "outbox"))).isEmpty();       // not whitelisted
        assertThat(metadata.updatableColumns(metadata.describe("orch", "v_failed_payments"))).isEmpty();
    }

    // --------------------------------------------------------------- single row

    @Test
    void updateRowChangesOnlyChangedColumnsAndAudits() {
        TableDetails t = metadata.describe("orch", "payment");
        Row before = paymentRow(t, "DBO-00000101");
        String status = before.get(t.indexOf("status"));
        String newStatus = "CANCELLED".equals(status) ? "COMPLETED" : "CANCELLED";

        List<ColumnValue> values = List.of(
                value("status", newStatus),
                value("attributes", before.get(t.indexOf("attributes"))));  // unchanged
        assertThat(updates.changes(t, before, values)).singleElement()
                .satisfies(ch -> assertThat(ch.column()).isEqualTo("status"));
        assertThat(updates.previewRow(t, before, values))
                .isEqualTo("update \"orch\".\"payment\" set \"status\" = '" + newStatus + "' where \"id\" = '"
                        + before.get(t.indexOf("id")) + "';");

        var result = updates.updateRow(t, before, values, "INC-UPD-1");
        assertThat(result.updated().get(result.columns().indexOf("status"))).isEqualTo(newStatus);
        assertThat(result.updated().get(result.columns().indexOf("amount"))).isEqualTo(before.get(t.indexOf("amount")));

        Map<String, Object> audit = lastAudit("DB_UPDATE");
        assertThat(audit).containsEntry("username", "operator").containsEntry("target", "orch.payment")
                .containsEntry("reason", "INC-UPD-1").containsEntry("success", true);
        assertThat((String) audit.get("details"))
                .contains("\"old\": \"" + status + "\"").contains("\"new\": \"" + newStatus + "\"");
    }

    @Test
    void jsonAndNullValues() {
        TableDetails t = metadata.describe("orch", "retry_task");
        long id = superuser.queryForObject("insert into orch.retry_task (payment_id, step_name, reason) "
                + "values (1, 'NOTIFY', 'старая причина') returning id", Long.class);
        Row row = data.fetch(t, List.of(new Filter("id", FilterOp.EQ, String.valueOf(id))), List.of(), 0, 1).get(0);

        updates.updateRow(t, row, List.of(
                new ColumnValue("reason", ValueMode.NULL, null),
                value("run_at", "2026-10-01 10:00:00+03")), "INC-UPD-2");
        Map<String, Object> db = superuser.queryForMap(
                "select reason, run_at = '2026-10-01 07:00:00Z' as ok from orch.retry_task where id = ?", id);
        assertThat(db).containsEntry("reason", null).containsEntry("ok", true);
    }

    @Test
    void concurrentChangeIsDetectedAndNothingIsOverwritten() {
        TableDetails t = metadata.describe("orch", "payment");
        Row seen = paymentRow(t, "DBO-00000102");
        // the application changes the row while the operator fills in the form
        superuser.update("update orch.payment set attributes = attributes || '{\"step\": 5}' where external_id = 'DBO-00000102'");

        assertThatThrownBy(() -> updates.updateRow(t, seen, List.of(value("status", "CANCELLED")), "INC-RACE"))
                .isInstanceOfSatisfying(DbConflictException.class, e -> {
                    assertThat(e.getMessage()).contains("attributes");
                    assertThat(e.current()).isNotNull().isNotEqualTo(seen);
                });
        assertThat(superuser.queryForObject("select status::text from orch.payment where external_id = 'DBO-00000102'",
                String.class)).isEqualTo(seen.get(t.indexOf("status")));
        assertThat(lastAudit("DB_UPDATE")).containsEntry("reason", "INC-RACE").containsEntry("success", false);

        // a retry based on the current row succeeds
        Row fresh = paymentRow(t, "DBO-00000102");
        updates.updateRow(t, fresh, List.of(value("status", "CANCELLED")), "INC-RACE-2");
    }

    @Test
    void deletedRowIsReported() {
        TableDetails t = metadata.describe("orch", "retry_task");
        long id = superuser.queryForObject(
                "insert into orch.retry_task (payment_id, step_name) values (1, 'X') returning id", Long.class);
        Row row = data.fetch(t, List.of(new Filter("id", FilterOp.EQ, String.valueOf(id))), List.of(), 0, 1).get(0);
        superuser.update("delete from orch.retry_task where id = ?", id);

        assertThatThrownBy(() -> updates.updateRow(t, row, List.of(value("reason", "x")), "INC"))
                .isInstanceOfSatisfying(DbConflictException.class, e -> assertThat(e.current()).isNull());
    }

    @Test
    void rowLockedByOrchestratorFailsFastWithReadableMessage() throws Exception {
        TableDetails t = metadata.describe("orch", "payment");
        Row row = paymentRow(t, "DBO-00000103");
        var pg = OrchestratorDb.container();
        try (Connection orchestrator = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            orchestrator.setAutoCommit(false);
            orchestrator.createStatement().execute("select * from orch.payment where external_id = 'DBO-00000103' for update");

            long started = System.currentTimeMillis();
            assertThatThrownBy(() -> updates.updateRow(t, row, List.of(value("status", "CANCELLED")), "INC-LOCK"))
                    .isInstanceOfSatisfying(DbException.class, e -> {
                        assertThat(e.sqlState()).isEqualTo("55P03");
                        assertThat(e.getMessage()).contains("заблокированы другим процессом");
                    });
            assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
            orchestrator.rollback();
        }
    }

    @Test
    void guards() {
        TableDetails t = metadata.describe("orch", "payment");
        Row row = paymentRow(t, "DBO-00000104");

        assertThatThrownBy(() -> updates.updateRow(t, row, List.of(value("amount", "1.00")), "INC"))
                .isInstanceOf(DbException.class).hasMessageContaining("запрещено");
        assertThatThrownBy(() -> updates.updateRow(t, row, List.of(value("id", "1")), "INC"))
                .isInstanceOf(DbException.class).hasMessageContaining("запрещено");
        assertThatThrownBy(() -> updates.updateRow(t, row, List.of(new ColumnValue("status", ValueMode.NULL, null)), "INC"))
                .isInstanceOf(DbException.class).hasMessageContaining("NOT NULL");
        assertThatThrownBy(() -> updates.updateRow(t, row, List.of(value("status", "CANCELLED")), " "))
                .isInstanceOf(DbException.class).hasMessageContaining("обоснование");
        assertThatThrownBy(() -> updates.updateRow(t, row,
                List.of(value("status", row.get(t.indexOf("status")))), "INC"))
                .isInstanceOf(DbException.class).hasMessageContaining("Нет изменений");
        assertThatThrownBy(() -> updates.updateRow(t, row, List.of(value("status", "NO_SUCH_STATUS")), "INC"))
                .isInstanceOf(DbException.class)
                .satisfies(e -> assertThat(((DbException) e).sqlState()).isEqualTo("22P02"));

        TestUsers.loginAs("viewer", Roles.VIEWER);
        assertThatThrownBy(() -> updates.updateRow(t, row, List.of(value("status", "CANCELLED")), "INC"))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- by filter

    private static final List<Filter> THREE = List.of(
            new Filter("external_id", FilterOp.IN, "DBO-00000201, DBO-00000202, DBO-00000203"));

    @Test
    void bulkUpdateByFilter() {
        TableDetails t = metadata.describe("orch", "payment");
        List<ColumnValue> set = List.of(value("attributes", "{\"manual\": true}"));

        BulkPreview preview = updates.previewBulk(t, THREE, set);
        assertThat(preview.count()).isEqualTo(3);
        assertThat(preview.columns()).containsExactly("id", "attributes");
        assertThat(preview.sample()).hasSize(3);
        assertThat(preview.sql()).startsWith("update \"orch\".\"payment\" set \"attributes\" = '{\"manual\": true}' where");

        assertThat(updates.updateBulk(t, THREE, set, 3, "INC-BULK").rows()).isEqualTo(3);
        assertThat(superuser.queryForObject("select count(*) from orch.payment where attributes = '{\"manual\": true}' "
                + "and external_id in ('DBO-00000201', 'DBO-00000202', 'DBO-00000203')", Long.class)).isEqualTo(3);

        Map<String, Object> audit = lastAudit("DB_BULK_UPDATE");
        assertThat(audit).containsEntry("success", true).containsEntry("reason", "INC-BULK");
        // snapshot of every row's previous values (attributes contained channel)
        assertThat((String) audit.get("details")).contains("\"rows\": 3").contains("\"before\": [")
                .contains("channel");
    }

    @Test
    void bulkUpdateAbortsWhenRowCountChanged() {
        TableDetails t = metadata.describe("orch", "payment");
        List<Filter> filter = List.of(new Filter("external_id", FilterOp.IN, "DBO-00000301, DBO-00000302"),
                new Filter("status", FilterOp.NE, "CANCELLED"));
        List<ColumnValue> set = List.of(value("status", "CANCELLED"));
        superuser.update("update orch.payment set status = 'CREATED' where external_id in ('DBO-00000301', 'DBO-00000302')");

        long confirmed = updates.previewBulk(t, filter, set).count();
        assertThat(confirmed).isEqualTo(2);
        // while the operator was confirming, one row left the filter
        superuser.update("update orch.payment set status = 'CANCELLED' where external_id = 'DBO-00000302'");

        assertThatThrownBy(() -> updates.updateBulk(t, filter, set, confirmed, "INC-BULK-RACE"))
                .isInstanceOf(DbConflictException.class).hasMessageContaining("подтверждено 2");
        assertThat(superuser.queryForObject("select status::text from orch.payment where external_id = 'DBO-00000301'",
                String.class)).isEqualTo("CREATED");
        assertThat(lastAudit("DB_BULK_UPDATE")).containsEntry("success", false);
    }

    @Test
    void bulkGuards() {
        TableDetails t = metadata.describe("orch", "payment");
        List<ColumnValue> set = List.of(value("status", "CANCELLED"));

        assertThatThrownBy(() -> updates.previewBulk(t, List.of(), set))
                .isInstanceOf(DbException.class).hasMessageContaining("без фильтра");
        assertThatThrownBy(() -> updates.previewBulk(t, THREE, List.of(value("amount", "1"))))
                .isInstanceOf(DbException.class).hasMessageContaining("запрещено");

        List<Filter> many = List.of(new Filter("currency", FilterOp.EQ, "RUB"));
        long count = updates.previewBulk(t, many, set).count();
        assertThat(count).isGreaterThan(50);
        assertThatThrownBy(() -> updates.updateBulk(t, many, set, count, "INC"))
                .isInstanceOf(DbException.class).hasMessageContaining("лимита 50");
        assertThat(superuser.queryForObject("select count(*) from orch.payment where status = 'CANCELLED' "
                + "and currency = 'RUB'", Long.class)).isLessThan(count);
    }

    @Test
    void bulkUpdateWithCompositeKeyOnPartitionedTable() {
        TableDetails t = metadata.describe("orch", "event_log");
        assertThat(t.primaryKey()).containsExactly("id", "created_at");
        List<Filter> filter = List.of(new Filter("id", FilterOp.IN, "1, 2"));
        List<ColumnValue> set = List.of(value("payload", "{\"to\": \"FIXED\"}"));

        long count = updates.previewBulk(t, filter, set).count();
        assertThat(count).isEqualTo(2);
        assertThat(updates.updateBulk(t, filter, set, count, "INC-EVT").rows()).isEqualTo(2);
        assertThat(superuser.queryForObject("select count(*) from orch.event_log where id in (1, 2) "
                + "and payload->>'to' = 'FIXED'", Long.class)).isEqualTo(2);
    }

    // ------------------------------------------------------- several users at once

    @Test
    void concurrentEditsOfTheSameRowNeverLoseUpdates() throws Exception {
        TableDetails t = metadata.describe("orch", "payment");
        Row seen = paymentRow(t, "DBO-00000401");
        int users = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(users);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<String>> outcomes = new java.util.ArrayList<>();
        for (int i = 0; i < users; i++) {
            String user = "operator-" + i;
            String status = i % 2 == 0 ? "CANCELLED" : "COMPLETED";
            outcomes.add(pool.submit(() -> {
                TestUsers.loginAs(user, Roles.OPERATOR, Roles.VIEWER);
                start.await();
                try {
                    updates.updateRow(t, seen, List.of(value("attributes", "{\"by\": \"" + user + "\"}"),
                            value("status", status)), "INC-PAR");
                    return "ok:" + user;
                } catch (DbConflictException e) {
                    return "conflict";
                } catch (DbException e) {
                    return "55P03".equals(e.sqlState()) ? "locked" : "error:" + e.getMessage();
                } finally {
                    TestUsers.logout();
                }
            }));
        }
        start.countDown();
        List<String> results = new java.util.ArrayList<>();
        for (var f : outcomes) results.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        // exactly one edit wins, every other user is told the row changed (or is busy) — none silently overwrites it
        List<String> winners = results.stream().filter(r -> r.startsWith("ok:")).toList();
        assertThat(winners).hasSize(1);
        assertThat(results).allMatch(r -> r.startsWith("ok:") || r.equals("conflict") || r.equals("locked"));
        String winner = winners.get(0).substring(3);
        assertThat(superuser.queryForObject("select attributes->>'by' from orch.payment where external_id = 'DBO-00000401'",
                String.class)).isEqualTo(winner);
        assertThat(superuser.queryForObject("select count(*) from admin_console.audit_log "
                + "where reason = 'INC-PAR' and success", Long.class)).isEqualTo(1);
    }
}
