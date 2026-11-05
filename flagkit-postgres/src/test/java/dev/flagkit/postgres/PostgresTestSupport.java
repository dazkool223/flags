package dev.flagkit.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Helpers for tests that run against a real Postgres. Each test gets its own environment prefix. */
final class PostgresTestSupport {

    private static final AtomicInteger NEXT = new AtomicInteger();

    private PostgresTestSupport() {}

    static PostgreSQLContainer newContainer() {
        return new PostgreSQLContainer("postgres:18");
    }

    /** Creates a fresh environment (tables and roles) as the container's superuser. */
    static TablePrefix newEnvironment(PostgreSQLContainer postgres) throws SQLException {
        var prefix = TablePrefix.of("e" + NEXT.incrementAndGet() + "_");
        try (var connection = admin(postgres); var statement = connection.createStatement()) {
            statement.execute(PostgresSchema.schemaSql(prefix));
            statement.execute(PostgresSchema.rolesSql(prefix));
        }
        return prefix;
    }

    static Connection admin(PostgreSQLContainer postgres) throws SQLException {
        return connect(postgres, postgres.getUsername(), postgres.getPassword());
    }

    static Connection connect(PostgreSQLContainer postgres, String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    /** Creates a personal login in a group role and returns a data source that connects as it. */
    static DataSource login(PostgreSQLContainer postgres, String user, String groupRole) throws SQLException {
        try (var connection = admin(postgres); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + user + " LOGIN PASSWORD 'secret' IN ROLE " + groupRole);
        }
        return dataSource(postgres, user, "secret");
    }

    static DataSource dataSource(PostgreSQLContainer postgres, String user, String password) {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return connect(postgres, user, password);
            }

            @Override
            public Connection getConnection(String u, String p) throws SQLException {
                return connect(postgres, u, p);
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {}

            @Override
            public void setLoginTimeout(int seconds) {}

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
