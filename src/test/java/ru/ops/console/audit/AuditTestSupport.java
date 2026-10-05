package ru.ops.console.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ops.console.config.ConsoleProperties;

import javax.sql.DataSource;

public final class AuditTestSupport {

    private AuditTestSupport() {
    }

    /** AuditService as after context startup (creates the audit table). */
    public static AuditService auditService(DataSource ds, ConsoleProperties props) {
        AuditService audit = new AuditService(new JdbcTemplate(ds), new ObjectMapper(), props);
        audit.init();
        return audit;
    }

    /** File-only audit (console.audit.jdbc-enabled=false). */
    public static AuditService fileOnlyAuditService(ConsoleProperties props) {
        AuditService audit = new AuditService(new JdbcTemplate(), new ObjectMapper(), props);
        audit.init();
        return audit;
    }
}
