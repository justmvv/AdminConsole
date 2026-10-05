package ru.ops.console.db;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ops.console.audit.AuditService;
import ru.ops.console.audit.AuditTestSupport;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.db.DbModel.ColumnInfo;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.Filter;
import ru.ops.console.db.DbModel.FilterOp;
import ru.ops.console.db.DbModel.InsertResult;
import ru.ops.console.db.DbModel.Row;
import ru.ops.console.db.DbModel.Sort;
import ru.ops.console.db.DbModel.TableDetails;
import ru.ops.console.db.DbModel.TableInfo;
import ru.ops.console.db.DbModel.TableKind;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.security.Roles;
import ru.ops.console.support.OrchestratorDb;
import ru.ops.console.support.TestUsers;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DbMetadataService + DbDataService + AuditService against a real PostgreSQL with the demo orchestrator schema.
 */
@Testcontainers
class DbServicesIT {

    private static HikariDataSource ds;
    private static JdbcTemplate superuser;
    private static DbMetadataService metadata;
    private static DbDataService data;
    private static ReadOnlyJdbc readOnly;

    @BeforeAll
    static void setUp() {
        ConsoleProperties props = new ConsoleProperties();
        props.getDb().setSchemas(List.of("orch", "admin_console"));
        // payment_step is not whitelisted, dictionary_currency is, but the account has no INSERT on payment_step
        props.getDb().getInsert().setAllowedTables(List.of("orch.retry_task", "orch.outbox", "orch.dictionary_currency",
                "orch.v_failed_payments"));
        props.getDb().setMaxExportRows(50);

        ds = OrchestratorDb.consoleDataSource();
        var pg = OrchestratorDb.container();
        superuser = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));

        readOnly = new ReadOnlyJdbc(ds, props);
        metadata = new DbMetadataService(readOnly, props);
        AuditService audit = AuditTestSupport.auditService(ds, props);
        assertThat(audit.isJdbcEnabled()).isTrue();
        data = new DbDataService(readOnly, ds, metadata, audit, props);
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

    // ---------------------------------------------------------------- metadata

    @Test
    void schemasAreFilteredByConfig() {
        superuser.execute("create schema if not exists hidden; grant usage on schema hidden to console");
        assertThat(metadata.schemas()).containsExactly("admin_console", "orch");
        assertThatThrownBy(() -> metadata.tables("hidden")).isInstanceOf(DbException.class);
        assertThatThrownBy(() -> metadata.tables("pg_catalog")).isInstanceOf(DbException.class);
    }

    @Test
    void tablesHidePartitionsAndKnowInsertRights() {
        Map<String, TableInfo> tables = metadata.tables("orch").stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.ref().name(), t -> t));
        assertThat(tables).containsKeys("payment", "payment_step", "outbox", "retry_task", "event_log",
                "v_failed_payments");
        assertThat(tables).doesNotContainKeys("event_log_2026", "event_log_default");
        assertThat(tables.get("event_log").kind()).isEqualTo(TableKind.PARTITIONED);
        assertThat(tables.get("v_failed_payments").kind()).isEqualTo(TableKind.VIEW);

        assertThat(metadata.isInsertAllowed(tables.get("retry_task"))).isTrue();
        // not whitelisted
        assertThat(metadata.isInsertAllowed(tables.get("payment"))).isFalse();
        // the DB account has no INSERT privilege
        assertThat(tables.get("payment_step").canInsert()).isFalse();
        // the view is whitelisted, but it is not a table
        assertThat(metadata.isInsertAllowed(tables.get("v_failed_payments"))).isFalse();
    }

    @Test
    void describePaymentColumns() {
        TableDetails t = metadata.describe("orch", "payment");
        assertThat(t.primaryKey()).containsExactly("id");

        ColumnInfo id = t.column("id");
        assertThat(id.identity()).isEqualTo("d");
        assertThat(id.isGeneratedAlways()).isFalse();
        assertThat(id.primaryKey()).isTrue();

        ColumnInfo status = t.column("status");
        assertThat(status.isEnum()).isTrue();
        assertThat(status.enumValues()).startsWith("CREATED", "VALIDATED");

        assertThat(t.column("amount").dataType()).isEqualTo("numeric(19,2)");
        assertThat(t.column("amount").castType()).isEqualTo("numeric");
        assertThat(t.column("attributes").isJson()).isTrue();
        assertThat(t.column("payment_uid").isUuid()).isTrue();
        assertThat(t.column("created_at").isDateTime()).isTrue();

        assertThat(t.constraints()).extracting(DbModel.ConstraintInfo::type)
                .contains("PRIMARY KEY", "UNIQUE", "CHECK");
        assertThat(t.indexes()).extracting(DbModel.IndexInfo::name).contains("payment_status_idx");
        assertThat(t.info().comment()).isEqualTo("Платёж — корневая сущность процесса");
    }

    @Test
    void describeGeneratedColumns() {
        TableDetails t = metadata.describe("orch", "payment_step");
        assertThat(t.column("id").isGeneratedAlways()).isTrue();
        assertThat(t.column("duration_ms").generated()).isEqualTo("s");
        assertThat(t.column("duration_ms").isGeneratedAlways()).isTrue();
    }

    @Test
    void describeUnknownTableFails() {
        assertThatThrownBy(() -> metadata.describe("orch", "no_such_table"))
                .isInstanceOf(DbException.class)
                .hasMessageContaining("не найдена");
    }

    // ----------------------------------------------------------------- reading

    @Test
    void fetchDefaultOrderIsNewestFirstByPk() {
        TableDetails t = metadata.describe("orch", "payment");
        List<Row> rows = data.fetch(t, List.of(), List.of(), 0, 3);
        assertThat(rows).hasSize(3);
        long first = Long.parseLong(rows.get(0).get(t.indexOf("id")));
        long second = Long.parseLong(rows.get(1).get(t.indexOf("id")));
        assertThat(first).isGreaterThan(second);
    }

    @Test
    void fetchPagination() {
        TableDetails t = metadata.describe("orch", "dictionary_currency");
        List<Sort> byCode = List.of(new Sort("code", true));
        List<Row> page1 = data.fetch(t, List.of(), byCode, 0, 2);
        List<Row> page2 = data.fetch(t, List.of(), byCode, 2, 2);
        assertThat(page1).extracting(r -> r.get(0)).containsExactly("CNY", "EUR");
        assertThat(page2).extracting(r -> r.get(0)).containsExactly("RUB", "USD");
    }

    @Test
    void filtersByEnumNumericJsonAndNull() {
        TableDetails t = metadata.describe("orch", "payment");
        long failed = data.count(t, List.of(new Filter("status", FilterOp.EQ, "FAILED")));
        long total = data.count(t, List.of());
        assertThat(failed).isPositive().isLessThan(total);

        // numeric is compared as a number, not as a string
        long big = data.count(t, List.of(new Filter("amount", FilterOp.GT, "999.99")));
        long bigAsText = superuser.queryForObject("select count(*) from orch.payment where amount > 999.99", Long.class);
        assertThat(big).isEqualTo(bigAsText);

        // substring search inside jsonb
        long web = data.count(t, List.of(new Filter("attributes", FilterOp.CONTAINS, "\"web\"")));
        assertThat(web).isPositive();

        assertThat(data.count(t, List.of(new Filter("purpose", FilterOp.IS_NULL, null)))).isZero();

        long inList = data.count(t, List.of(new Filter("currency", FilterOp.IN, "USD, CNY")));
        long usd = data.count(t, List.of(new Filter("currency", FilterOp.EQ, "USD")));
        long cny = data.count(t, List.of(new Filter("currency", FilterOp.EQ, "CNY")));
        assertThat(inList).isEqualTo(usd + cny);
    }

    @Test
    void likeWildcardsInFilterAreLiteral() {
        TableDetails t = metadata.describe("orch", "payment");
        assertThat(data.count(t, List.of(new Filter("external_id", FilterOp.CONTAINS, "%")))).isZero();
        assertThat(data.count(t, List.of(new Filter("external_id", FilterOp.STARTS_WITH, "DBO-_")))).isZero();
        assertThat(data.count(t, List.of(new Filter("external_id", FilterOp.STARTS_WITH, "dbo-")))).isZero();
        assertThat(data.count(t, List.of(new Filter("external_id", FilterOp.STARTS_WITH, "DBO-0000000")))).isEqualTo(9);
    }

    @Test
    void filterValueCannotInjectSql() {
        TableDetails t = metadata.describe("orch", "payment");
        assertThat(data.count(t, List.of(new Filter("external_id", FilterOp.EQ, "x' or '1'='1")))).isZero();
        assertThat(data.count(t, List.of(new Filter("purpose", FilterOp.CONTAINS, "'; delete from orch.payment; --"))))
                .isZero();
        assertThat(superuser.queryForObject("select count(*) from orch.payment", Long.class)).isGreaterThan(0);
    }

    @Test
    void unknownColumnInFilterIsRejected() {
        TableDetails t = metadata.describe("orch", "payment");
        assertThatThrownBy(() -> data.count(t, List.of(new Filter("id\"; drop table x; --", FilterOp.EQ, "1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void badFilterValueGivesReadableDbError() {
        TableDetails t = metadata.describe("orch", "payment");
        assertThatThrownBy(() -> data.count(t, List.of(new Filter("amount", FilterOp.EQ, "not-a-number"))))
                .isInstanceOf(DbException.class);
        // the connection is still usable after an error
        assertThat(data.count(t, List.of())).isPositive();
    }

    @Test
    void describeQueryShowsLiterals() {
        TableDetails t = metadata.describe("orch", "payment");
        String sql = data.describeQuery(t, List.of(new Filter("external_id", FilterOp.EQ, "O'Neil")), List.of());
        assertThat(sql).contains("where \"external_id\" = 'O''Neil'").contains("order by \"id\" desc");
    }

    // ---------------------------------------------------------- read only

    @Test
    void readPathRejectsWritesAndDoesNotPoisonThePool() {
        assertThatThrownBy(() -> readOnly.read("operator", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into orch.dictionary_currency (code, name) values ('XXX', 'hack')")) {
                return ps.executeUpdate();
            }
        })).isInstanceOf(DbException.class)
                .satisfies(e -> assertThat(((DbException) e).sqlState()).isEqualTo("25006"));

        assertThat(superuser.queryForObject("select count(*) from orch.dictionary_currency where code = 'XXX'",
                Long.class)).isZero();

        // The pool has one connection: after a read-only transaction an insert on the same connection must work
        TableDetails t = metadata.describe("orch", "dictionary_currency");
        InsertResult r = data.insert(t, List.of(
                new ColumnValue("code", ValueMode.VALUE, "KZT"),
                new ColumnValue("name", ValueMode.VALUE, "Тенге"),
                new ColumnValue("active", ValueMode.DEFAULT, null)), "INC-1");
        assertThat(r.inserted().get(r.columns().indexOf("active"))).isEqualTo("t");
    }

    @Test
    void sessionIsLabeledWithUser() {
        String name = readOnly.read("ivanov", c -> {
            try (PreparedStatement ps = c.prepareStatement("select current_setting('application_name')");
                 var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertThat(name).isEqualTo("admin-console:ivanov");
    }

    // ------------------------------------------------------------------ insert

    @Test
    void insertWritesRowAndAuditInSameTransaction() {
        TableDetails t = metadata.describe("orch", "retry_task");
        List<ColumnValue> values = List.of(
                new ColumnValue("id", ValueMode.DEFAULT, null),
                new ColumnValue("payment_id", ValueMode.VALUE, "42"),
                new ColumnValue("step_name", ValueMode.VALUE, "SEND_TO_CORE"),
                new ColumnValue("run_at", ValueMode.DEFAULT, null),
                new ColumnValue("reason", ValueMode.VALUE, "повтор после таймаута АБС"),
                new ColumnValue("created_by", ValueMode.DEFAULT, null));

        assertThat(data.previewInsert(t, values))
                .contains("insert into \"orch\".\"retry_task\" (\"payment_id\", \"step_name\", \"reason\")")
                .contains("values ('42', 'SEND_TO_CORE', 'повтор после таймаута АБС')");

        InsertResult r = data.insert(t, values, "INC-100500");
        String id = r.inserted().get(r.columns().indexOf("id"));
        assertThat(r.inserted().get(r.columns().indexOf("created_by"))).isEqualTo("console");

        Map<String, Object> audit = superuser.queryForMap(
                "select username, action, target, reason, success, details->'inserted'->>'id' as inserted_id "
                        + "from admin_console.audit_log where action = 'DB_INSERT' and reason = 'INC-100500'");
        assertThat(audit).containsEntry("username", "operator")
                .containsEntry("target", "orch.retry_task")
                .containsEntry("success", true)
                .containsEntry("inserted_id", id);
    }

    @Test
    void insertTypedValuesAreConvertedByServer() {
        TableDetails t = metadata.describe("orch", "outbox");
        InsertResult r = data.insert(t, List.of(
                new ColumnValue("aggregate_id", ValueMode.VALUE, "DBO-TEST"),
                new ColumnValue("topic", ValueMode.VALUE, "payments.events"),
                new ColumnValue("msg_key", ValueMode.NULL, null),
                new ColumnValue("payload", ValueMode.VALUE, "{\"status\": \"COMPLETED\", \"amount\": 10.5}"),
                new ColumnValue("created_at", ValueMode.VALUE, "2026-09-01 12:00:00+03")), "INC-2");
        String id = r.inserted().get(r.columns().indexOf("id"));
        Map<String, Object> row = superuser.queryForMap(
                "select jsonb_typeof(payload) t, payload->>'status' s, msg_key, created_at = '2026-09-01 09:00:00Z' ok "
                        + "from orch.outbox where id = ?", Long.parseLong(id));
        assertThat(row).containsEntry("t", "object").containsEntry("s", "COMPLETED")
                .containsEntry("msg_key", null).containsEntry("ok", true);
    }

    @Test
    void failedInsertIsRolledBackAndAudited() {
        TableDetails t = metadata.describe("orch", "retry_task");
        long before = superuser.queryForObject("select count(*) from orch.retry_task", Long.class);
        assertThatThrownBy(() -> data.insert(t, List.of(
                new ColumnValue("payment_id", ValueMode.VALUE, "999999999"),   // FK violation
                new ColumnValue("step_name", ValueMode.VALUE, "X")), "INC-FK"))
                .isInstanceOf(DbException.class)
                .satisfies(e -> assertThat(((DbException) e).sqlState()).isEqualTo("23503"));
        assertThat(superuser.queryForObject("select count(*) from orch.retry_task", Long.class)).isEqualTo(before);
        assertThat(superuser.queryForObject(
                "select success from admin_console.audit_log where reason = 'INC-FK'", Boolean.class)).isFalse();
    }

    @Test
    void databaseErrorTextContainsRowDataButTheLogDoesNot() {
        TableDetails t = metadata.describe("orch", "retry_task");
        DbException e = org.junit.jupiter.api.Assertions.assertThrows(DbException.class, () -> data.insert(t, List.of(
                new ColumnValue("payment_id", ValueMode.VALUE, "999999999"),
                new ColumnValue("step_name", ValueMode.VALUE, "X"),
                new ColumnValue("reason", ValueMode.VALUE, "secret-value-40702810")), "INC-LOG"));
        // PostgreSQL puts the values into the error text (Detail: Key (payment_id)=(999999999) ...)
        assertThat(e.getMessage()).contains("999999999");
        String logged = ru.ops.console.config.SafeLog.describe(e);
        assertThat(logged).contains("23503").doesNotContain("999999999").doesNotContain("secret-value");
    }

    @Test
    void insertGuards() {
        TableDetails retry = metadata.describe("orch", "retry_task");
        List<ColumnValue> ok = List.of(
                new ColumnValue("payment_id", ValueMode.VALUE, "1"),
                new ColumnValue("step_name", ValueMode.VALUE, "X"));

        assertThatThrownBy(() -> data.insert(retry, ok, "  "))
                .isInstanceOf(DbException.class).hasMessageContaining("обоснование");

        assertThatThrownBy(() -> data.insert(metadata.describe("orch", "payment"), List.of(), "INC"))
                .isInstanceOf(DbException.class).hasMessageContaining("запрещена");

        assertThatThrownBy(() -> data.insert(retry, List.of(
                new ColumnValue("payment_id", ValueMode.VALUE, "1"),
                new ColumnValue("step_name", ValueMode.NULL, null)), "INC"))
                .isInstanceOf(DbException.class).hasMessageContaining("NOT NULL");

        TableDetails step = metadata.describe("orch", "payment_step");
        assertThatThrownBy(() -> data.previewInsert(step, List.of(new ColumnValue("duration_ms", ValueMode.VALUE, "1"))))
                .isInstanceOf(DbException.class).hasMessageContaining("GENERATED ALWAYS");

        TestUsers.loginAs("viewer", Roles.VIEWER);
        assertThatThrownBy(() -> data.insert(retry, ok, "INC")).isInstanceOf(AccessDeniedException.class);
    }

    // ------------------------------------------------------------------- CSV

    @Test
    void csvExportQuotesAndIsAudited() {
        TableDetails t = metadata.describe("orch", "dictionary_currency");
        superuser.update("insert into orch.dictionary_currency (code, name) values ('ZZZ', 'Точка; с \"кавычками\"') "
                + "on conflict do nothing");
        byte[] csv = data.exportCsv("operator", t, List.of(new Filter("code", FilterOp.EQ, "ZZZ")), List.of());
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿code;name;active\r\n");
        assertThat(text).contains("ZZZ;\"Точка; с \"\"кавычками\"\"\";t\r\n");
        assertThat(superuser.queryForObject(
                "select count(*) from admin_console.audit_log where action = 'DB_EXPORT' and target = 'orch.dictionary_currency'",
                Long.class)).isPositive();
    }

    @Test
    void csvExportIsCappedByConfig() {
        TableDetails t = metadata.describe("orch", "payment");
        String text = new String(data.exportCsv("operator", t, List.of(), List.of()), StandardCharsets.UTF_8);
        assertThat(text.split("\r\n")).hasSize(1 + 50);
    }
}
