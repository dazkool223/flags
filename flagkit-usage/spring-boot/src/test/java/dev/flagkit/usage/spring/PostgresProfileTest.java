package dev.flagkit.usage.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flagkit.FlagStore;
import dev.flagkit.Match;
import dev.flagkit.postgres.FlagWriter;
import dev.flagkit.postgres.PostgresFlagStore;
import dev.flagkit.postgres.PostgresSchema;
import dev.flagkit.postgres.TablePrefix;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The {@code postgres} profile: Spring Boot's DataSource feeds a PostgresFlagStore as the reader login. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresProfileTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    private static final List<String> CART = List.of("tea", "mug");

    @Test
    void theAppReadsTheGraphThatAnOperatorWroteToPostgres() throws SQLException {
        var prefix = TablePrefix.of("app_");
        createEnvironment(prefix);
        seed(prefix);

        try (var context = start("app_")) {
            assertThat(context.getBean(FlagStore.class)).isInstanceOf(PostgresFlagStore.class);

            var checkout = context.getBean(CheckoutService.class);
            var current = context.getBean(CurrentCustomer.class);

            current.set(new Customer("ana", "eu", "premium", false));
            assertThat(checkout.checkout(CART)).isEqualTo("new checkout: tea, mug");

            current.set(new Customer("di", "us", "premium", false));
            assertThat(checkout.checkout(CART)).isEqualTo("legacy checkout: tea, mug");
        }
    }

    @Test
    void eagerLoadStopsStartupWhenTheStoreHoldsAConditionNobodyRegistered() throws SQLException {
        var prefix = TablePrefix.of("bad_");
        createEnvironment(prefix);
        var writer = new FlagWriter(POSTGRES_ADMIN(), prefix);
        writer.create("checkout", Match.ALL, "moon-phase", Map.of());

        assertThatThrownBy(() -> start("bad_").close()).hasStackTraceContaining("moon-phase");
    }

    /**
     * Starts the app the way a deployment would: the profile and connection settings arrive as
     * command-line arguments (they could equally be environment variables). The test passes the
     * password. The real profile leaves it out and the driver finds it in .pgpass.
     */
    private static org.springframework.context.ConfigurableApplicationContext start(String prefix) {
        return new SpringApplicationBuilder(UsageApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.profiles.active=postgres",
                        "--spring.main.banner-mode=off",
                        "--spring.datasource.url=" + jdbcUrl(),
                        "--spring.datasource.username=orders_app",
                        "--spring.datasource.password=app",
                        "--flagkit.table-prefix=" + prefix);
    }

    /** The container has no TLS, so the test relaxes sslmode. The real profile defaults to verify-full. */
    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/"
                + POSTGRES.getDatabaseName() + "?sslmode=prefer";
    }

    private static void createEnvironment(TablePrefix prefix) throws SQLException {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(PostgresSchema.schemaSql(prefix));
            statement.execute(PostgresSchema.rolesSql(prefix));
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'orders_app') "
                    + "THEN CREATE ROLE orders_app LOGIN PASSWORD 'app'; END IF; END $$");
            statement.execute("GRANT " + PostgresSchema.readerRole(prefix) + " TO orders_app");
        }
    }

    private static javax.sql.DataSource POSTGRES_ADMIN() {
        var source = new org.postgresql.ds.PGSimpleDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    /** What an operator would do with the CLI: build the graph and switch it on. */
    private static void seed(TablePrefix prefix) {
        var writer = new FlagWriter(POSTGRES_ADMIN(), prefix);
        writer.create("beta-region", Match.ALL, "region-in", Map.of("regions", "eu,uk"));
        writer.create("premium-plan", Match.ALL, "plan-is", Map.of("plan", "premium"));
        writer.create("internal-staff", Match.ALL, "staff", Map.of());
        writer.create("premium-or-internal", Match.ANY, null, Map.of());
        writer.create("checkout", Match.ALL, null, Map.of());
        writer.addPrerequisite("premium-or-internal", "premium-plan");
        writer.addPrerequisite("premium-or-internal", "internal-staff");
        writer.addPrerequisite("checkout", "beta-region");
        writer.addPrerequisite("checkout", "premium-or-internal");
        for (var key : List.of("beta-region", "premium-plan", "internal-staff", "premium-or-internal", "checkout")) {
            writer.setSwitch(key, true);
        }
    }
}
