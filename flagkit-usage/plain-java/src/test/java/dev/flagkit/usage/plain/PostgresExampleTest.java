package dev.flagkit.usage.plain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flagkit.postgres.FlagWriter;
import dev.flagkit.postgres.PostgresSchema;
import dev.flagkit.postgres.TablePrefix;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The whole Postgres lifecycle from {@link PostgresExample}: DBA, operator, application. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresExampleTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    @Test
    void dbaSetsUpOperatorSeedsApplicationEvaluatesAndRefreshes() throws SQLException {
        var prefix = TablePrefix.of("usage_");

        // 1. The DBA creates the tables and roles, then one login per person or application.
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(PostgresSchema.schemaSql(prefix));
            statement.execute(PostgresSchema.rolesSql(prefix));
            statement.execute("CREATE ROLE orders_app LOGIN PASSWORD 'app' IN ROLE "
                    + PostgresSchema.readerRole(prefix));
            statement.execute("CREATE ROLE alice LOGIN PASSWORD 'alice' IN ROLE "
                    + PostgresSchema.writerRole(prefix));
        }

        // 2. Alice, an operator, builds the graph. In real life she would use the CLI, and the
        //    password would come from .pgpass.
        var operator = PostgresExample.dataSource(POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), "alice", "prefer");
        var writer = new FlagWriter(withPassword(operator, "alice"), prefix);
        PostgresExample.seed(writer);

        // 3. The application evaluates as the reader.
        var app = PostgresExample.dataSource(POSTGRES.getHost(), POSTGRES.getMappedPort(5432),
                POSTGRES.getDatabaseName(), "orders_app", "prefer");
        var evaluator = PostgresExample.evaluator(withPassword(app, "app"), prefix);

        assertThat(evaluator.evaluate(CheckoutFlags.CHECKOUT, InMemoryExample.ANA).enabled()).isTrue();
        assertThat(evaluator.evaluate(CheckoutFlags.CHECKOUT, InMemoryExample.DI).enabled()).isFalse();

        // 4. Alice kills the feature during an incident. The application keeps its old snapshot
        //    until it asks for a new one.
        writer.setSwitch("checkout", false);
        assertThat(evaluator.evaluate(CheckoutFlags.CHECKOUT, InMemoryExample.ANA).enabled()).isTrue();

        evaluator.refresh();
        var afterRefresh = evaluator.evaluate(CheckoutFlags.CHECKOUT, InMemoryExample.ANA);
        assertThat(afterRefresh.enabled()).isFalse();
        assertThat(afterRefresh.reason().kind().name()).isEqualTo("SWITCH_OFF");
    }

    /** Test-only: the real tool and the example read passwords from .pgpass, never from code. */
    private static javax.sql.DataSource withPassword(javax.sql.DataSource source, String password) {
        ((org.postgresql.ds.PGSimpleDataSource) source).setPassword(password);
        return source;
    }
}
