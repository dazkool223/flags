package dev.flagkit.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.flagkit.postgres.PostgresSchema;
import dev.flagkit.postgres.TablePrefix;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs commands in-process against a real Postgres, with the password coming only from a .pgpass file. */
@Testcontainers(disabledWithoutDocker = true)
class FlagkitCliTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    static final AtomicInteger NEXT = new AtomicInteger();

    record Result(int exit, String out, String err) {}

    @TempDir Path dir;
    Path configFile;

    @BeforeEach
    void environment() throws Exception {
        assumeTrue(POSTGRES.getHost().equals("localhost") || POSTGRES.getHost().equals("127.0.0.1"),
                "the CLI requires verified TLS for non-local hosts; the test container has none");
        configFile = dir.resolve("config.properties");
        pgpass(POSTGRES.getPassword());
        // The driver reads the password file named by this property, or by PGPASSFILE.
        System.setProperty("org.postgresql.pgpassfile", dir.resolve("pgpass").toString());
        writeConfig("dev", POSTGRES.getMappedPort(5432), prefixWithSchema());
    }

    @AfterEach
    void forgetPgpass() {
        System.clearProperty("org.postgresql.pgpassfile");
    }

    private String prefixWithSchema() throws SQLException {
        var prefix = TablePrefix.of("c" + NEXT.incrementAndGet() + "_");
        try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var s = c.createStatement()) {
            s.execute(PostgresSchema.schemaSql(prefix));
            s.execute(PostgresSchema.rolesSql(prefix));
        }
        return prefix.value();
    }

    private void pgpass(String password) throws IOException {
        Files.writeString(dir.resolve("pgpass"), POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + ":"
                + POSTGRES.getDatabaseName() + ":" + POSTGRES.getUsername() + ":" + password + "\n");
    }

    private void writeConfig(String env, int port, String prefix) throws IOException {
        Files.writeString(configFile, String.join("\n",
                env + ".host=" + POSTGRES.getHost(),
                env + ".port=" + port,
                env + ".database=" + POSTGRES.getDatabaseName(),
                env + ".user=" + POSTGRES.getUsername(),
                env + ".prefix=" + prefix,
                ""));
    }

    private Result run(String... args) {
        var all = new java.util.ArrayList<>(List.of(args));
        all.addAll(List.of("--env", "dev", "--config", configFile.toString()));
        return runRaw(all.toArray(String[]::new));
    }

    private Result runRaw(String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        var cmd = FlagkitCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        var exit = cmd.execute(args);
        return new Result(exit, out.toString(), err.toString());
    }

    private void ok(String... args) {
        var result = run(args);
        assertThat(result.exit()).as("%s -> %s", List.of(args), result.err()).isZero();
    }

    // --- one happy path per command ----------------------------------------------------------

    @Test
    void createDeleteAndSwitch() {
        assertThat(run("create", "checkout")).isEqualTo(new Result(0, "created checkout\n", ""));
        assertThat(run("switch", "checkout", "on").out()).isEqualTo("checkout switched on\n");
        assertThat(run("show", "checkout").out()).contains("switch: on");
        assertThat(run("switch", "checkout", "OFF").exit()).isZero();
        assertThat(run("delete", "checkout").out()).isEqualTo("deleted checkout\n");
        assertThat(run("graph").out()).isEqualTo("(no flags)\n");
    }

    @Test
    void createTakesMatchConditionAndParams() {
        ok("create", "beta", "--match", "ANY", "--condition", "region-in", "-p", "regions=eu,uk", "-p", "salt=x");

        assertThat(run("show", "beta").out()).isEqualTo("""
                key: beta
                switch: off
                match: ANY
                condition: region-in
                params:
                  regions = eu,uk
                  salt = x
                prerequisites: (none)
                """);
    }

    @Test
    void conditionMatchAndParamCommands() {
        ok("create", "a");

        ok("condition", "set", "a", "pct");
        ok("param", "set", "a", "percent", "10");
        ok("param", "set", "a", "percent", "50");
        ok("param", "set", "a", "salt", "x");
        ok("param", "unset", "a", "salt");
        ok("match", "a", "any");

        assertThat(run("show", "a").out()).contains("match: ANY", "condition: pct", "percent = 50")
                .doesNotContain("salt");
        ok("condition", "clear", "a");
        assertThat(run("show", "a").out()).contains("condition: (none)");
    }

    @Test
    void prerequisiteAddAndRemove() {
        ok("create", "a");
        ok("create", "b");

        assertThat(run("prerequisite", "add", "a", "b").out()).isEqualTo("a now requires b\n");
        assertThat(run("show", "a").out()).contains("prerequisites:\n  b\n");
        assertThat(run("prerequisite", "remove", "a", "b").out()).isEqualTo("a no longer requires b\n");
        assertThat(run("show", "a").out()).contains("prerequisites: (none)");
    }

    @Test
    void graphPrintsTheTreeWithSharedPrerequisitesMarked() {
        for (var key : List.of("checkout", "payments", "beta", "ledger", "bank")) {
            ok("create", key);
        }
        ok("switch", "checkout", "on");
        ok("prerequisite", "add", "checkout", "payments");
        ok("prerequisite", "add", "checkout", "beta");
        ok("prerequisite", "add", "payments", "ledger");
        ok("prerequisite", "add", "beta", "ledger");
        ok("prerequisite", "add", "ledger", "bank");

        assertThat(run("graph").out()).isEqualTo("""
                checkout [on, ALL]
                |-- payments [off, ALL]
                |   `-- ledger [off, ALL]
                |       `-- bank [off, ALL]
                `-- beta [off, ALL]
                    `-- ledger [off, ALL] (shown above)
                """);
    }

    // --- rejections and exit codes -----------------------------------------------------------

    @Test
    void aCycleIsRejectedWithItsPathAndExitCodeOne() {
        ok("create", "a");
        ok("create", "b");
        ok("prerequisite", "add", "a", "b");

        var result = run("prerequisite", "add", "b", "a");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("rejected:", "b -> a -> b");
    }

    @Test
    void deletingAFlagOthersDependOnIsRejected() {
        ok("create", "base");
        ok("create", "x");
        ok("prerequisite", "add", "x", "base");

        var result = run("delete", "base");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("prerequisite of: x");
    }

    @Test
    void showingAMissingFlagFails() {
        var result = run("show", "ghost");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("ghost");
    }

    @Test
    void usageErrorsExitWithTwo() {
        assertThat(run("switch", "a", "maybe").exit()).isEqualTo(2);
        assertThat(runRaw("create", "a").exit()).isEqualTo(2);
    }

    // --- export ------------------------------------------------------------------------------

    @Test
    void exportIsStableAcrossRunsAndIndependentOfCreationOrder() throws Exception {
        for (var key : List.of("zeta", "alpha", "mid")) {
            ok("create", key);
        }
        ok("param", "set", "mid", "b", "2");
        ok("param", "set", "mid", "a", "1");
        ok("prerequisite", "add", "zeta", "alpha");
        ok("switch", "zeta", "on");

        var first = run("export").out();
        var second = run("export").out();

        assertThat(second).isEqualTo(first);
        assertThat(first.indexOf("\"alpha\"")).isLessThan(first.indexOf("\"mid\""));
        assertThat(first.indexOf("\"mid\"")).isLessThan(first.indexOf("\"zeta\""));
        assertThat(first.indexOf("\"a\": \"1\"")).isLessThan(first.indexOf("\"b\": \"2\""));
        assertThat(first).doesNotContain("\r");

        // Same graph built in another order, in another environment, exports identically.
        writeConfig("dev", POSTGRES.getMappedPort(5432), prefixWithSchema());
        for (var key : List.of("mid", "zeta", "alpha")) {
            ok("create", key);
        }
        ok("switch", "zeta", "on");
        ok("param", "set", "mid", "a", "1");
        ok("param", "set", "mid", "b", "2");
        ok("prerequisite", "add", "zeta", "alpha");
        assertThat(run("export").out()).isEqualTo(first);
    }

    @Test
    void exportCanWriteAFile() throws Exception {
        ok("create", "a");
        var file = dir.resolve("snapshot.json");

        var result = run("export", "--output", file.toString());

        assertThat(result.out()).isEqualTo("exported to " + file + "\n");
        assertThat(Files.readString(file)).isEqualTo(run("export").out()).contains("\"key\": \"a\"");
    }

    @Test
    void exportOfAnEmptyEnvironmentIsValidJson() {
        assertThat(run("export").out()).isEqualTo("{\n  \"flags\": []\n}\n");
    }

    // --- connection errors never leak secrets ------------------------------------------------

    @Test
    void anUnreachableDatabaseShowsHostDatabaseAndUserButNoConnectionString() throws Exception {
        writeConfig("dev", 1, "");

        var result = run("graph");

        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.err()).contains("host=" + POSTGRES.getHost(), "database=" + POSTGRES.getDatabaseName(),
                "user=" + POSTGRES.getUsername());
        assertThat(result.err() + result.out()).doesNotContain("jdbc:").doesNotContain(POSTGRES.getPassword());
    }

    @Test
    void aWrongPasswordInPgpassIsNotEchoed() throws Exception {
        pgpass("hunter2");

        var result = run("graph");

        assertThat(result.exit()).isEqualTo(3);
        assertThat(result.err() + result.out()).doesNotContain("hunter2").doesNotContain("jdbc:");
    }

    @Test
    void thereIsNoWayToPassAPassword() {
        for (var option : List.of("--password", "-W", "--pass")) {
            assertThat(run("graph", option, "hunter2").exit()).isEqualTo(2);
        }
    }
}
