package dev.flagkit.usage.plain;

import dev.flagkit.Evaluator;
import dev.flagkit.Match;
import dev.flagkit.postgres.FlagWriter;
import dev.flagkit.postgres.PostgresFlagStore;
import dev.flagkit.postgres.TablePrefix;
import java.util.Map;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Flags in Postgres. Three people are involved, and each uses a different database login.
 *
 * <ol>
 *   <li><b>A DBA</b> runs {@code PostgresSchema.schemaSql(prefix)} and then
 *       {@code PostgresSchema.rolesSql(prefix)} once per environment (or saves the two scripts
 *       and runs them through the usual migration process). Then creates a login for the
 *       application in the reader role and a personal login for each operator in the writer role.
 *   <li><b>An operator</b> changes flags with the {@code flagkit} command-line tool or, as
 *       {@link #seed} does, with {@link FlagWriter}. Every change is checked for cycles,
 *       serialized and written to the audit table under the operator's own login.
 *   <li><b>The application</b> connects as the reader and evaluates. It never writes.
 * </ol>
 *
 * <p>Passwords are never passed in code. The JDBC driver reads them from the user's
 * {@code .pgpass} file (or the file named by the PGPASSFILE environment variable).
 *
 * <p>Run: {@code PostgresExample seed} as an operator, then {@code PostgresExample evaluate} as
 * the application, with FLAGKIT_HOST, FLAGKIT_DB, FLAGKIT_USER and FLAGKIT_PREFIX set.
 */
public final class PostgresExample {

    private PostgresExample() {}

    public static void main(String[] args) {
        var prefix = TablePrefix.of(System.getenv().getOrDefault("FLAGKIT_PREFIX", ""));
        var dataSource = dataSource(required("FLAGKIT_HOST"), 5432, required("FLAGKIT_DB"),
                required("FLAGKIT_USER"), System.getenv().getOrDefault("FLAGKIT_SSLMODE", "verify-full"));
        switch (args.length == 0 ? "" : args[0]) {
            case "seed" -> seed(new FlagWriter(dataSource, prefix));
            case "evaluate" -> {
                var evaluator = evaluator(dataSource, prefix);
                for (var customer : java.util.List.of(InMemoryExample.ANA, InMemoryExample.BO,
                        InMemoryExample.CY, InMemoryExample.DI)) {
                    System.out.println(customer.id() + " -> "
                            + InMemoryExample.describe(evaluator.evaluate(CheckoutFlags.CHECKOUT, customer)));
                }
            }
            default -> System.err.println("usage: PostgresExample seed|evaluate");
        }
    }

    /**
     * A plain JDBC data source. A connection pool works too: flagkit only borrows a connection
     * for each load or write and gives it back.
     */
    public static DataSource dataSource(String host, int port, String database, String user, String sslMode) {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {host});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(database);
        source.setUser(user);
        source.setSslMode(sslMode); // verify-full for any real host. Weaker modes are for local tests.
        return source;
    }

    /**
     * The application side. Use a data source that connects as the environment's reader login.
     * The prefix picks the environment: "prod_" and "staging_" are separate sets of tables.
     */
    public static Evaluator<Customer> evaluator(DataSource readerSource, TablePrefix prefix) {
        return CheckoutFlags.evaluator(new PostgresFlagStore(readerSource, prefix));
        // The graph loads on first use. Pass the evaluator to evaluator.load() at startup if a
        // broken graph should stop the deployment. After an operator changes flags, call
        // evaluator.refresh(). It swaps in a new snapshot atomically, so calls running at that
        // moment see the old graph or the new one, never a mix. Nothing refreshes by itself.
    }

    /**
     * The operator side. Use a data source that connects as a personal login in the writer role.
     * New flags start with their switch off, so a flag does nothing until someone turns it on.
     * Each call below is one transaction, one audit row and one revision bump.
     */
    public static void seed(FlagWriter writer) {
        writer.create("beta-region", Match.ALL, "region-in", Map.of("regions", "eu,uk"));
        writer.create("premium-plan", Match.ALL, "plan-is", Map.of("plan", "premium"));
        writer.create("internal-staff", Match.ALL, "staff", Map.of());
        writer.create("premium-or-internal", Match.ANY, null, Map.of());
        writer.create("checkout", Match.ALL, null, Map.of());

        writer.addPrerequisite("premium-or-internal", "premium-plan");
        writer.addPrerequisite("premium-or-internal", "internal-staff");
        writer.addPrerequisite("checkout", "beta-region");
        writer.addPrerequisite("checkout", "premium-or-internal");

        for (var key : java.util.List.of("beta-region", "premium-plan", "internal-staff",
                "premium-or-internal", "checkout")) {
            writer.setSwitch(key, true);
        }
        // Adding a prerequisite that would close a loop throws CycleDetectedException with the
        // path. Deleting a flag that others require throws FlagWriteException naming them.
    }

    private static String required(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name);
        }
        return value;
    }
}
