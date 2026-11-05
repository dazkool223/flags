package dev.flagkit.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flagkit.Evaluator;
import dev.flagkit.FlagConfig;
import dev.flagkit.FlagDeclaration;
import dev.flagkit.Match;
import dev.flagkit.Reason;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class PostgresFlagStoreTest {

    record Ctx(String region) {}

    @Container
    static final PostgreSQLContainer POSTGRES = PostgresTestSupport.newContainer();

    TablePrefix prefix;
    DataSource operator;
    FlagWriter writer;
    PostgresFlagStore store;

    @BeforeEach
    void freshEnvironment() throws SQLException {
        prefix = PostgresTestSupport.newEnvironment(POSTGRES);
        operator = PostgresTestSupport.login(POSTGRES, "alice_" + prefix.value(), PostgresSchema.writerRole(prefix));
        writer = new FlagWriter(operator, prefix);
        store = new PostgresFlagStore(operator, prefix);
    }

    // --- the store feeds the evaluator -------------------------------------------------------

    @Test
    void aGraphBuiltThroughTheWriterEvaluatesEndToEnd() {
        writer.create("checkout", Match.ALL, "region-in", Map.of("regions", "eu,uk"));
        writer.create("payments", Match.ALL, null, Map.of());
        writer.addPrerequisite("checkout", "payments");
        writer.setSwitch("checkout", true);
        writer.setSwitch("payments", true);
        var evaluator = Evaluator.<Ctx>builder(store)
                .condition(new dev.flagkit.Condition<>() {
                    public String name() { return "region-in"; }
                    public boolean test(Ctx c, Map<String, String> p) {
                        return List.of(p.get("regions").split(",")).contains(c.region());
                    }
                }).build();
        var checkout = new FlagDeclaration.PermanentFlag("checkout");

        assertThat(evaluator.evaluate(checkout, new Ctx("eu")).enabled()).isTrue();
        var outsideRegion = evaluator.evaluate(checkout, new Ctx("us"));
        assertThat(outsideRegion.enabled()).isFalse();
        assertThat(outsideRegion.reason().kind()).isEqualTo(Reason.Kind.CONDITION_FALSE);

        writer.setSwitch("payments", false);
        evaluator.refresh();
        var blocked = evaluator.evaluate(checkout, new Ctx("eu"));
        assertThat(blocked.reason()).isEqualTo(new Reason(Reason.Kind.PREREQUISITE_FAILED, "payments"));
    }

    @Test
    void loadReturnsEveryFlagWithItsSwitchMatchConditionParamsAndOrderedPrerequisites() {
        writer.create("a", Match.ALL, null, Map.of());
        writer.create("b", Match.ALL, null, Map.of());
        writer.create("c", Match.ANY, "pct", Map.of("percent", "10"));
        writer.addPrerequisite("c", "b");
        writer.addPrerequisite("c", "a");
        writer.setSwitch("c", true);

        var flags = store.load();

        assertThat(flags).extracting(FlagConfig::key).containsExactly("a", "b", "c");
        var c = flags.get(2);
        assertThat(c.on()).isTrue();
        assertThat(c.match()).isEqualTo(Match.ANY);
        assertThat(c.conditionName()).isEqualTo("pct");
        assertThat(c.params()).isEqualTo(Map.of("percent", "10"));
        assertThat(c.prerequisites()).containsExactly("b", "a");
    }

    @Test
    void environmentsWithDifferentPrefixesShareNothing() throws SQLException {
        writer.create("only-here", Match.ALL, null, Map.of());
        var other = PostgresTestSupport.newEnvironment(POSTGRES);

        var otherStore = new PostgresFlagStore(PostgresTestSupport.dataSource(
                POSTGRES, POSTGRES.getUsername(), POSTGRES.getPassword()), other);

        assertThat(otherStore.load()).isEmpty();
        assertThat(store.load()).hasSize(1);
    }

    @Test
    void aLoadSeesEachWriteWhole() throws Exception {
        writer.create("left", Match.ALL, null, Map.of());
        writer.create("right", Match.ALL, null, Map.of());
        var stop = new AtomicBoolean();
        var failure = new ArrayList<Throwable>();
        var flipper = Executors.newSingleThreadExecutor();
        flipper.submit(() -> {
            // Raw SQL flips two flags in one transaction; a consistent load never sees them differ.
            try (var c = PostgresTestSupport.admin(POSTGRES)) {
                c.setAutoCommit(false);
                var on = false;
                while (!stop.get()) {
                    on = !on;
                    try (var s = c.prepareStatement("UPDATE " + prefix.value() + "flag SET enabled = ?")) {
                        s.setBoolean(1, on);
                        s.executeUpdate();
                    }
                    c.commit();
                }
            } catch (Throwable t) {
                failure.add(t);
            }
        });
        try {
            for (var i = 0; i < 300; i++) {
                var flags = store.load();
                assertThat(flags.get(0).on()).isEqualTo(flags.get(1).on());
            }
        } finally {
            stop.set(true);
            flipper.shutdown();
            flipper.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(failure).isEmpty();
    }

    // --- graph rules -------------------------------------------------------------------------

    @Test
    void addingAPrerequisiteThatClosesACycleIsRejectedWithThePath() {
        writer.create("a", Match.ALL, null, Map.of());
        writer.create("b", Match.ALL, null, Map.of());
        writer.create("c", Match.ALL, null, Map.of());
        writer.addPrerequisite("a", "b");
        writer.addPrerequisite("b", "c");

        assertThatThrownBy(() -> writer.addPrerequisite("c", "a"))
                .isInstanceOfSatisfying(CycleDetectedException.class,
                        e -> assertThat(e.path()).containsExactly("c", "a", "b", "c"))
                .hasMessageContaining("c -> a -> b -> c");
        assertThat(store.load().stream().filter(f -> f.key().equals("c")).findFirst().orElseThrow()
                .prerequisites()).isEmpty();
    }

    @Test
    void aFlagCannotBeItsOwnPrerequisite() {
        writer.create("a", Match.ALL, null, Map.of());

        assertThatThrownBy(() -> writer.addPrerequisite("a", "a")).isInstanceOf(CycleDetectedException.class);
    }

    @Test
    void aDiamondIsNotACycle() {
        for (var key : List.of("top", "left", "right", "bottom")) {
            writer.create(key, Match.ALL, null, Map.of());
        }
        writer.addPrerequisite("top", "left");
        writer.addPrerequisite("top", "right");
        writer.addPrerequisite("left", "bottom");
        writer.addPrerequisite("right", "bottom");

        assertThat(store.load()).hasSize(4);
    }

    @Test
    void aPrerequisiteOrFlagThatDoesNotExistIsRejected() {
        writer.create("a", Match.ALL, null, Map.of());

        assertThatThrownBy(() -> writer.addPrerequisite("a", "ghost"))
                .isInstanceOf(FlagWriteException.class).hasMessageContaining("ghost");
        assertThatThrownBy(() -> writer.addPrerequisite("ghost", "a"))
                .isInstanceOf(FlagWriteException.class).hasMessageContaining("ghost");
        assertThatThrownBy(() -> writer.setSwitch("ghost", true))
                .isInstanceOf(FlagWriteException.class).hasMessageContaining("ghost");
    }

    @Test
    void deletingAFlagOthersDependOnIsRejectedListingThem() {
        writer.create("base", Match.ALL, null, Map.of());
        writer.create("x", Match.ALL, null, Map.of());
        writer.create("y", Match.ALL, null, Map.of());
        writer.addPrerequisite("y", "base");
        writer.addPrerequisite("x", "base");

        assertThatThrownBy(() -> writer.delete("base"))
                .isInstanceOf(FlagWriteException.class).hasMessageContaining("x, y");

        writer.removePrerequisite("x", "base");
        writer.removePrerequisite("y", "base");
        writer.delete("base");
        assertThat(store.load()).extracting(FlagConfig::key).containsExactly("x", "y");
    }

    @Test
    void deletingAFlagRemovesItsOwnPrerequisitesAndParams() {
        writer.create("base", Match.ALL, null, Map.of());
        writer.create("child", Match.ALL, "pct", Map.of("percent", "5"));
        writer.addPrerequisite("child", "base");

        writer.delete("child");
        writer.delete("base");

        assertThat(store.load()).isEmpty();
    }

    @Test
    void creatingAFlagThatExistsIsRejected() {
        writer.create("a", Match.ALL, null, Map.of());

        assertThatThrownBy(() -> writer.create("a", Match.ALL, null, Map.of()))
                .isInstanceOf(FlagWriteException.class).hasMessageContaining("already exists");
    }

    @Test
    void conditionMatchAndParamChangesAreStored() {
        writer.create("a", Match.ALL, null, Map.of());

        writer.setCondition("a", "pct");
        writer.setParam("a", "percent", "10");
        writer.setParam("a", "percent", "50");
        writer.setParam("a", "salt", "x");
        writer.unsetParam("a", "salt");
        writer.setMatch("a", Match.ANY);

        var a = store.load().get(0);
        assertThat(a.conditionName()).isEqualTo("pct");
        assertThat(a.params()).isEqualTo(Map.of("percent", "50"));
        assertThat(a.match()).isEqualTo(Match.ANY);

        writer.clearCondition("a");
        assertThat(store.load().get(0).conditionName()).isNull();
    }

    @Test
    void twoOperatorsAddingOppositePrerequisitesAtOnceLeaveExactlyOneStanding() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (var round = 0; round < 15; round++) {
                var a = "a" + round;
                var b = "b" + round;
                writer.create(a, Match.ALL, null, Map.of());
                writer.create(b, Match.ALL, null, Map.of());
                var barrier = new CyclicBarrier(2);
                var first = pool.submit(() -> attempt(barrier, a, b));
                var second = pool.submit(() -> attempt(barrier, b, a));

                var succeeded = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);

                assertThat(succeeded).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean attempt(CyclicBarrier barrier, String flag, String prerequisite) throws Exception {
        var ownWriter = new FlagWriter(operator, prefix);
        barrier.await(10, TimeUnit.SECONDS);
        try {
            ownWriter.addPrerequisite(flag, prerequisite);
            return true;
        } catch (CycleDetectedException e) {
            return false;
        }
    }

    // --- revision and audit ------------------------------------------------------------------

    @Test
    void everyWriteBumpsTheRevisionAndInsertsOneAuditRowByTheSessionUser() throws Exception {
        var login = "alice_" + prefix.value();

        writer.create("a", Match.ALL, null, Map.of());
        writer.setSwitch("a", true);
        writer.setParam("a", "k", "v\"q");

        try (var c = PostgresTestSupport.admin(POSTGRES); var s = c.createStatement()) {
            var meta = s.executeQuery("SELECT revision FROM " + prefix.value() + "meta");
            meta.next();
            assertThat(meta.getLong(1)).isEqualTo(3);

            var audit = s.executeQuery("SELECT actor, operation, flag_key, before_value, after_value, occurred_at"
                    + " FROM " + prefix.value() + "audit ORDER BY id");
            audit.next();
            assertThat(audit.getString("actor")).isEqualTo(login);
            assertThat(audit.getString("operation")).isEqualTo("CREATE");
            assertThat(audit.getString("before_value")).isNull();
            assertThat(audit.getString("after_value")).contains("\"switch\": false");
            assertThat(audit.getTimestamp("occurred_at")).isNotNull();
            audit.next();
            assertThat(audit.getString("operation")).isEqualTo("SWITCH_ON");
            assertThat(audit.getString("before_value")).contains("\"switch\": false");
            assertThat(audit.getString("after_value")).contains("\"switch\": true");
            audit.next();
            assertThat(audit.getString("after_value")).contains("\"k\": \"v\\\"q\"");
            assertThat(audit.next()).isFalse();
        }
    }

    @Test
    void aRejectedWriteLeavesNoRevisionBumpAndNoAuditRow() throws Exception {
        writer.create("a", Match.ALL, null, Map.of());

        assertThatThrownBy(() -> writer.addPrerequisite("a", "ghost")).isInstanceOf(FlagWriteException.class);

        try (var c = PostgresTestSupport.admin(POSTGRES); var s = c.createStatement()) {
            var revision = s.executeQuery("SELECT revision FROM " + prefix.value() + "meta");
            revision.next();
            assertThat(revision.getLong(1)).isEqualTo(1);
            var rows = s.executeQuery("SELECT count(*) FROM " + prefix.value() + "audit");
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(1);
        }
    }

    @Test
    void deletedFlagsKeepTheirAuditHistory() throws Exception {
        writer.create("a", Match.ALL, null, Map.of());
        writer.delete("a");

        try (var c = PostgresTestSupport.admin(POSTGRES); var s = c.createStatement()) {
            var rows = s.executeQuery("SELECT operation, before_value, after_value FROM "
                    + prefix.value() + "audit WHERE flag_key = 'a' ORDER BY id DESC");
            rows.next();
            assertThat(rows.getString("operation")).isEqualTo("DELETE");
            assertThat(rows.getString("before_value")).isNotNull();
            assertThat(rows.getString("after_value")).isNull();
        }
    }

    // --- roles -------------------------------------------------------------------------------

    @Test
    void readerCanReadButNotWrite() throws Exception {
        writer.create("a", Match.ALL, null, Map.of());
        var reader = PostgresTestSupport.login(POSTGRES, "app_" + prefix.value(), PostgresSchema.readerRole(prefix));

        assertThat(new PostgresFlagStore(reader, prefix).load()).hasSize(1);
        assertThatThrownBy(() -> new FlagWriter(reader, prefix).setSwitch("a", true))
                .isInstanceOf(FlagStoreException.class).hasMessageContaining("permission denied");
    }

    @Test
    void writerCannotUpdateOrDeleteAuditRowsOrChooseTheActor() throws Exception {
        writer.create("a", Match.ALL, null, Map.of());
        var audit = prefix.value() + "audit";

        try (var c = operator.getConnection(); var s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE " + audit + " SET operation = 'x'"))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM " + audit))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("TRUNCATE " + audit))
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate(
                    "INSERT INTO " + audit + " (actor, operation, flag_key) VALUES ('bob', 'CREATE', 'a')"))
                    .hasMessageContaining("permission denied");
        }
    }
}
