package ru.ops.console.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL with the demo orchestrator schema from docker/postgres/init.sql — the same as in docker compose.
 * One container for all test classes; the console connects with the restricted account console.
 */
public final class OrchestratorDb {

    private static PostgreSQLContainer<?> container;

    private OrchestratorDb() {
    }

    public static synchronized PostgreSQLContainer<?> container() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("orchestrator")
                    .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/init.sql"),
                            "/docker-entrypoint-initdb.d/01-init.sql");
            container.start();
        }
        return container;
    }

    /** Pool with the console's account (as in production: Hikari, a small pool — connections are reused). */
    public static HikariDataSource consoleDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(container().getJdbcUrl());
        cfg.setUsername("console");
        cfg.setPassword("console");
        cfg.setMaximumPoolSize(1);
        return new HikariDataSource(cfg);
    }
}
