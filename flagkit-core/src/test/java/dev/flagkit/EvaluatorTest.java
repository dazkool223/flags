package dev.flagkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EvaluatorTest {

    record Ctx(String region) {}

    private static final FlagDeclaration CHECKOUT = new FlagDeclaration.PermanentFlag("checkout");

    private static Evaluator<Ctx> evaluatorOver(FlagConfig... flags) {
        return Evaluator.<Ctx>builder(new InMemoryFlagStore(flags)).build();
    }

    @Test
    void flagWithSwitchOnAndNothingElseIsEnabled() {
        var evaluator = evaluatorOver(FlagConfig.of("checkout").on().build());

        var result = evaluator.evaluate(CHECKOUT, new Ctx("eu"));

        assertThat(result.enabled()).isTrue();
        assertThat(result.reason().kind()).isEqualTo(Reason.Kind.ENABLED);
    }

    @Test
    void flagWithSwitchOffIsDisabled() {
        var evaluator = evaluatorOver(FlagConfig.of("checkout").off().build());

        var result = evaluator.evaluate(CHECKOUT, new Ctx("eu"));

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason().kind()).isEqualTo(Reason.Kind.SWITCH_OFF);
    }

    private static Result eval(Evaluator<Ctx> evaluator, String key) {
        return evaluator.evaluate(new FlagDeclaration.PermanentFlag(key), new Ctx("eu"));
    }

    @Test
    void allMatchFailsNamingTheFirstDisabledPrerequisite() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().match(Match.ALL).requires("b", "c").build(),
                FlagConfig.of("b").on().build(),
                FlagConfig.of("c").off().build());

        var result = eval(evaluator, "a");

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason()).isEqualTo(new Reason(Reason.Kind.PREREQUISITE_FAILED, "c"));
    }

    @Test
    void allMatchIsEnabledWhenEveryPrerequisiteIsEnabled() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().match(Match.ALL).requires("b", "c").build(),
                FlagConfig.of("b").on().build(),
                FlagConfig.of("c").on().build());

        assertThat(eval(evaluator, "a").enabled()).isTrue();
    }

    @Test
    void anyMatchIsEnabledWhenOnePrerequisiteIsEnabled() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().match(Match.ANY).requires("b", "c").build(),
                FlagConfig.of("b").off().build(),
                FlagConfig.of("c").on().build());

        assertThat(eval(evaluator, "a").enabled()).isTrue();
    }

    @Test
    void anyMatchFailsWhenNoPrerequisiteIsEnabled() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().match(Match.ANY).requires("b", "c").build(),
                FlagConfig.of("b").off().build(),
                FlagConfig.of("c").off().build());

        var result = eval(evaluator, "a");

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason().kind()).isEqualTo(Reason.Kind.PREREQUISITE_FAILED);
    }

    @Test
    void flagWithNoPrerequisitesSatisfiesBothMatches() {
        var evaluator = evaluatorOver(
                FlagConfig.of("all").on().match(Match.ALL).build(),
                FlagConfig.of("any").on().match(Match.ANY).build());

        assertThat(eval(evaluator, "all").enabled()).isTrue();
        assertThat(eval(evaluator, "any").enabled()).isTrue();
    }

    @Test
    void switchOffStopsBeforePrerequisitesAreConsidered() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").off().requires("b").build(),
                FlagConfig.of("b").on().build());

        assertThat(eval(evaluator, "a").reason().kind()).isEqualTo(Reason.Kind.SWITCH_OFF);
    }

    @Test
    void resolvesAPrerequisiteChainOfAnyDepth() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().requires("b").build(),
                FlagConfig.of("b").on().requires("c").build(),
                FlagConfig.of("c").on().requires("d").build(),
                FlagConfig.of("d").off().build());

        var result = eval(evaluator, "a");

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason()).isEqualTo(new Reason(Reason.Kind.PREREQUISITE_FAILED, "b"));
    }

    @Test
    void anOrGateInsideAnAndGate() {
        var evaluator = evaluatorOver(
                FlagConfig.of("checkout").on().match(Match.ALL).requires("beta", "audience").build(),
                FlagConfig.of("beta").on().build(),
                FlagConfig.of("audience").on().match(Match.ANY).requires("premium", "internal").build(),
                FlagConfig.of("premium").off().build(),
                FlagConfig.of("internal").on().build());

        assertThat(eval(evaluator, "checkout").enabled()).isTrue();
    }

    /** A condition that records how often it ran and answers with a fixed result. */
    private static final class Probe implements Condition<Ctx> {
        private final String name;
        private final boolean answer;
        int calls;

        Probe(String name, boolean answer) {
            this.name = name;
            this.answer = answer;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean test(Ctx context, Map<String, String> params) {
            calls++;
            return answer;
        }
    }

    private static Evaluator<Ctx> evaluatorWith(Condition<Ctx> condition, FlagConfig... flags) {
        return Evaluator.<Ctx>builder(new InMemoryFlagStore(flags)).condition(condition).build();
    }

    @Test
    void conditionDecidesWhenSwitchAndPrerequisitesPass() {
        var yes = new Probe("yes", true);
        var no = new Probe("no", false);
        var evaluator = Evaluator.<Ctx>builder(new InMemoryFlagStore(
                        FlagConfig.of("a").on().condition("yes").build(),
                        FlagConfig.of("b").on().condition("no").build()))
                .condition(yes).condition(no).build();

        assertThat(eval(evaluator, "a").enabled()).isTrue();
        var result = eval(evaluator, "b");
        assertThat(result.enabled()).isFalse();
        assertThat(result.reason().kind()).isEqualTo(Reason.Kind.CONDITION_FALSE);
    }

    @Test
    void conditionReceivesTheTypedContextAndTheFlagsParams() {
        var seen = new Object[2];
        Condition<Ctx> regionIn = new Condition<>() {
            public String name() { return "region-in"; }
            public boolean test(Ctx context, Map<String, String> params) {
                seen[0] = context.region();
                seen[1] = params;
                return params.get("regions").contains(context.region());
            }
        };
        var evaluator = evaluatorWith(regionIn,
                FlagConfig.of("a").on().condition("region-in", Map.of("regions", "eu,us")).build());

        assertThat(eval(evaluator, "a").enabled()).isTrue();
        assertThat(seen[0]).isEqualTo("eu");
        assertThat(seen[1]).isEqualTo(Map.of("regions", "eu,us"));
    }

    @Test
    void conditionDoesNotRunWhenSwitchIsOff() {
        var probe = new Probe("p", true);
        var evaluator = evaluatorWith(probe, FlagConfig.of("a").off().condition("p").build());

        eval(evaluator, "a");

        assertThat(probe.calls).isZero();
    }

    @Test
    void conditionDoesNotRunWhenAPrerequisiteFails() {
        var probe = new Probe("p", true);
        var evaluator = evaluatorWith(probe,
                FlagConfig.of("a").on().requires("b").condition("p").build(),
                FlagConfig.of("b").off().build());

        eval(evaluator, "a");

        assertThat(probe.calls).isZero();
    }

    @Test
    void allStopsAtTheFirstDisabledPrerequisite() {
        var later = new Probe("later", true);
        var evaluator = evaluatorWith(later,
                FlagConfig.of("a").on().match(Match.ALL).requires("b", "c").build(),
                FlagConfig.of("b").off().build(),
                FlagConfig.of("c").on().condition("later").build());

        eval(evaluator, "a");

        assertThat(later.calls).isZero();
    }

    @Test
    void anyStopsAtTheFirstEnabledPrerequisite() {
        var later = new Probe("later", true);
        var evaluator = evaluatorWith(later,
                FlagConfig.of("a").on().match(Match.ANY).requires("b", "c").build(),
                FlagConfig.of("b").on().build(),
                FlagConfig.of("c").on().condition("later").build());

        eval(evaluator, "a");

        assertThat(later.calls).isZero();
    }

    @Test
    void aSharedPrerequisiteRunsOncePerEvaluationAndIsNotACycle() {
        var shared = new Probe("shared", true);
        var evaluator = evaluatorWith(shared,
                FlagConfig.of("top").on().requires("left", "right").build(),
                FlagConfig.of("left").on().requires("base").build(),
                FlagConfig.of("right").on().requires("base").build(),
                FlagConfig.of("base").on().condition("shared").build());

        assertThat(eval(evaluator, "top").enabled()).isTrue();
        assertThat(shared.calls).isEqualTo(1);

        eval(evaluator, "top");
        assertThat(shared.calls).isEqualTo(2);
    }

    @Test
    void loadFailsNamingAnUnknownCondition() {
        var evaluator = evaluatorOver(FlagConfig.of("a").on().condition("ghost").build());

        assertThatThrownBy(() -> eval(evaluator, "a"))
                .isInstanceOf(InvalidGraphException.class)
                .hasMessageContaining("ghost")
                .hasMessageContaining("a");
    }

    @Test
    void loadFailsNamingACycleAndItsPath() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().requires("b").build(),
                FlagConfig.of("b").on().requires("c").build(),
                FlagConfig.of("c").on().requires("a").build());

        assertThatThrownBy(() -> eval(evaluator, "a"))
                .isInstanceOf(InvalidGraphException.class)
                .hasMessageContaining("a -> b -> c -> a");
    }

    @Test
    void loadFailsNamingADanglingPrerequisite() {
        var evaluator = evaluatorOver(FlagConfig.of("a").on().requires("missing").build());

        assertThatThrownBy(() -> eval(evaluator, "a"))
                .isInstanceOf(InvalidGraphException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void aDeclaredFlagMissingFromTheStoreIsDisabledAsNotFound() {
        var evaluator = evaluatorOver(FlagConfig.of("other").on().build());

        var result = eval(evaluator, "checkout");

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason().kind()).isEqualTo(Reason.Kind.FLAG_NOT_FOUND);
    }

    @Test
    void theGraphIsLoadedLazilyOnFirstEvaluation() {
        var loads = new AtomicInteger();
        var evaluator = Evaluator.<Ctx>builder(() -> {
            loads.incrementAndGet();
            return List.of(FlagConfig.of("a").on().build());
        }).build();

        assertThat(loads).hasValue(0);
        eval(evaluator, "a");
        eval(evaluator, "a");
        assertThat(loads).hasValue(1);
    }

    @Test
    void theGraphCanBeLoadedEagerly() {
        var loads = new AtomicInteger();
        var evaluator = Evaluator.<Ctx>builder(() -> {
            loads.incrementAndGet();
            return List.of(FlagConfig.of("a").on().build());
        }).build();

        evaluator.load();

        assertThat(loads).hasValue(1);
        eval(evaluator, "a");
        assertThat(loads).hasValue(1);
    }

    @Test
    void anUnreachableStoreMakesTheLoadThrow() {
        var evaluator = Evaluator.<Ctx>builder(() -> {
            throw new IllegalStateException("db down");
        }).build();

        assertThatThrownBy(() -> eval(evaluator, "a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
    }

    @Test
    void changesAreOnlyVisibleAfterRefresh() {
        var graph = new AtomicReference<>(List.of(FlagConfig.of("a").off().build()));
        var evaluator = Evaluator.<Ctx>builder(graph::get).build();
        assertThat(eval(evaluator, "a").enabled()).isFalse();

        graph.set(List.of(FlagConfig.of("a").on().build()));
        assertThat(eval(evaluator, "a").enabled()).isFalse();

        evaluator.refresh();
        assertThat(eval(evaluator, "a").enabled()).isTrue();
    }

    @Test
    void aFailedRefreshKeepsServingTheOldGraph() {
        var graph = new AtomicReference<>(List.of(FlagConfig.of("a").on().build()));
        var evaluator = Evaluator.<Ctx>builder(graph::get).build();
        eval(evaluator, "a");

        graph.set(List.of(FlagConfig.of("a").on().requires("missing").build()));
        assertThatThrownBy(evaluator::refresh).isInstanceOf(InvalidGraphException.class);

        assertThat(eval(evaluator, "a").enabled()).isTrue();
    }

    @Test
    void anEvaluationSeesOneSnapshotEvenIfARefreshLandsMidway() {
        var graph = new AtomicReference<>(List.of(
                FlagConfig.of("a").on().match(Match.ALL).requires("b", "c").build(),
                FlagConfig.of("b").on().condition("refresher").build(),
                FlagConfig.of("c").on().build()));
        var holder = new AtomicReference<Evaluator<Ctx>>();
        Condition<Ctx> refresher = new Condition<>() {
            public String name() { return "refresher"; }
            public boolean test(Ctx context, Map<String, String> params) {
                graph.set(List.of(
                        FlagConfig.of("a").on().match(Match.ALL).requires("b", "c").build(),
                        FlagConfig.of("b").on().build(),
                        FlagConfig.of("c").off().build()));
                holder.get().refresh();
                return true;
            }
        };
        var evaluator = Evaluator.<Ctx>builder(graph::get).condition(refresher).build();
        holder.set(evaluator);

        assertThat(eval(evaluator, "a").enabled()).isTrue();
        assertThat(eval(evaluator, "a").enabled()).isFalse();
    }

    private static Explanation explain(Evaluator<Ctx> evaluator, String key) {
        return evaluator.explain(new FlagDeclaration.PermanentFlag(key), new Ctx("eu"));
    }

    @Test
    void explainReturnsTheSameResultAsEvaluate() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().requires("b").build(),
                FlagConfig.of("b").off().build());

        assertThat(explain(evaluator, "a").result()).isEqualTo(eval(evaluator, "a"));
    }

    @Test
    void explainTracesTheFlagsVisitedThroughThePrerequisiteChain() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().match(Match.ALL).requires("b", "c").build(),
                FlagConfig.of("b").on().requires("d").build(),
                FlagConfig.of("c").on().build(),
                FlagConfig.of("d").off().build());

        var root = explain(evaluator, "a").root();

        assertThat(root.flagKey()).isEqualTo("a");
        assertThat(root.result().enabled()).isFalse();
        assertThat(root.prerequisites()).extracting(Explanation.Visit::flagKey).containsExactly("b");
        var b = root.prerequisites().get(0);
        assertThat(b.result().reason()).isEqualTo(new Reason(Reason.Kind.PREREQUISITE_FAILED, "d"));
        assertThat(b.prerequisites()).extracting(Explanation.Visit::flagKey).containsExactly("d");
        assertThat(b.prerequisites().get(0).result().reason().kind()).isEqualTo(Reason.Kind.SWITCH_OFF);
    }

    @Test
    void explainMarksASharedPrerequisiteAsCachedOnItsSecondVisit() {
        var evaluator = evaluatorOver(
                FlagConfig.of("top").on().requires("left", "right").build(),
                FlagConfig.of("left").on().requires("base").build(),
                FlagConfig.of("right").on().requires("base").build(),
                FlagConfig.of("base").on().build());

        var root = explain(evaluator, "top").root();

        var firstBase = root.prerequisites().get(0).prerequisites().get(0);
        var secondBase = root.prerequisites().get(1).prerequisites().get(0);
        assertThat(firstBase.cached()).isFalse();
        assertThat(secondBase.cached()).isTrue();
    }

    @Test
    void explainOfAMissingFlagIsASingleNotFoundVisit() {
        var evaluator = evaluatorOver(FlagConfig.of("other").on().build());

        var explanation = explain(evaluator, "checkout");

        assertThat(explanation.root().flagKey()).isEqualTo("checkout");
        assertThat(explanation.result().reason().kind()).isEqualTo(Reason.Kind.FLAG_NOT_FOUND);
        assertThat(explanation.root().prerequisites()).isEmpty();
    }

    /** f0 requires f1, f1 requires f2, and so on; the last flag has no prerequisites. */
    private static FlagConfig[] chainOfLength(int length) {
        var flags = new FlagConfig[length];
        for (int i = 0; i < length; i++) {
            var flag = FlagConfig.of("f" + i).on();
            flags[i] = (i + 1 < length ? flag.requires("f" + (i + 1)) : flag).build();
        }
        return flags;
    }

    @Test
    void aPrerequisiteChainHasNoDepthLimit() {
        var evaluator = evaluatorOver(chainOfLength(50_000));

        assertThat(eval(evaluator, "f0").enabled()).isTrue();
    }

    @Test
    void explainAlsoHasNoDepthLimit() {
        var evaluator = evaluatorOver(chainOfLength(50_000));

        assertThat(explain(evaluator, "f0").result().enabled()).isTrue();
    }

    @Test
    void loadFailsNamingADuplicateFlagKey() {
        var evaluator = evaluatorOver(
                FlagConfig.of("a").on().build(),
                FlagConfig.of("a").off().build());

        assertThatThrownBy(() -> eval(evaluator, "a"))
                .isInstanceOf(InvalidGraphException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("a");
    }
}
