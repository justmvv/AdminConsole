package ru.ops.console.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.ops.console.config.ConsoleProperties;
import ru.ops.console.db.DbDataService;
import ru.ops.console.db.DbMetadataService;
import ru.ops.console.db.DbModel.ColumnValue;
import ru.ops.console.db.DbModel.ValueMode;
import ru.ops.console.db.ReadOnlyJdbc;
import ru.ops.console.security.Roles;
import ru.ops.console.support.OrchestratorDb;
import ru.ops.console.support.TestUsers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit log in a separate database: the console writes into the target system's DB only what the operator
 * changes, and creates no objects there.
 */
@Testcontainers
class SeparateAuditDatabaseIT {

    private static HikariDataSource target;
    private static AuditService audit;
    private static JdbcTemplate superuser;
    private static JdbcTemplate auditDb;

    @BeforeAll
    static void setUp() {
        var pg = OrchestratorDb.container();
        superuser = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        superuser.execute("create database console_audit");
        String auditUrl = pg.getJdbcUrl().replace("/orchestrator", "/console_audit");
        auditDb = new JdbcTemplate(new DriverManagerDataSource(auditUrl, pg.getUsername(), pg.getPassword()));

        ConsoleProperties props = new ConsoleProperties();
        props.getAudit().setJdbcTable("console_audit_log");
        props.getAudit().setJdbcUrl(auditUrl);
        props.getAudit().setJdbcUser(pg.getUsername());
        props.getAudit().setJdbcPassword(pg.getPassword());
        props.getDb().getInsert().setAllowedTables(List.of("orch.retry_task"));

        target = OrchestratorDb.consoleDataSource();
        audit = new AuditService(new JdbcTemplate(target), new ObjectMapper(), props);
        audit.init();
        ReadOnlyJdbc readOnly = new ReadOnlyJdbc(target, props);
        DbMetadataService metadata = new DbMetadataService(readOnly, props);
        DbDataService data = new DbDataService(readOnly, target, metadata, audit, props);

        TestUsers.loginAs("operator", Roles.OPERATOR, Roles.VIEWER);
        try {
            data.insert(metadata.describe("orch", "retry_task"), List.of(
                    new ColumnValue("payment_id", ValueMode.VALUE, "1"),
                    new ColumnValue("step_name", ValueMode.VALUE, "NOTIFY")), "INC-SEPARATE");
        } finally {
            TestUsers.logout();
        }
    }

    @AfterAll
    static void tearDown() {
        audit.close();
        target.close();
    }

    @Test
    void auditGoesToItsOwnDatabase() {
        assertThat(auditDb.queryForObject(
                "select count(*) from console_audit_log where action = 'DB_INSERT' and reason = 'INC-SEPARATE'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void nothingIsCreatedInTheTargetDatabase() {
        assertThat(superuser.queryForObject(
                "select count(*) from pg_tables where tablename = 'console_audit_log'", Long.class)).isZero();
        assertThat(superuser.queryForObject(
                "select count(*) from orch.retry_task where step_name = 'NOTIFY' and payment_id = 1", Long.class))
                .isPositive();
    }
}
