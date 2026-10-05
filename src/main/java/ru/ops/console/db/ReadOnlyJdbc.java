package ru.ops.console.db;

import org.springframework.stereotype.Component;
import ru.ops.console.config.ConsoleProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Runs queries in a READ ONLY transaction: even if modifying SQL slips into reading code,
 * PostgreSQL rejects it. The transaction is always rolled back.
 * Every session gets an application_name with the user name — visible to DBAs in pg_stat_activity.
 */
@Component
public class ReadOnlyJdbc {

    @FunctionalInterface
    public interface ConnectionCallback<T> {
        T apply(Connection c) throws SQLException;
    }

    private final DataSource dataSource;
    private final int timeoutSeconds;
    private final ConsoleProperties.Features features;

    public ReadOnlyJdbc(DataSource dataSource, ConsoleProperties props) {
        this.dataSource = dataSource;
        this.timeoutSeconds = props.getDb().getQueryTimeoutSeconds();
        this.features = props.getFeatures();
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** The DB section is disabled — never touch the target database (the audit log works separately). */
    public void requireEnabled() {
        if (!features.isDb()) throw new DbException("Раздел БД выключен в настройках (console.features.db=false)");
    }

    public <T> T read(String user, ConnectionCallback<T> callback) {
        requireEnabled();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            try {
                try (PreparedStatement ps = c.prepareStatement("set transaction read only")) {
                    ps.execute();
                }
                applicationName(c, user);
                return callback.apply(c);
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    static void applicationName(Connection c, String user) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select set_config('application_name', ?, true)")) {
            String name = "admin-console:" + (user == null ? "?" : user);
            ps.setString(1, name.length() > 63 ? name.substring(0, 63) : name);
            ps.execute();
        }
    }
}
