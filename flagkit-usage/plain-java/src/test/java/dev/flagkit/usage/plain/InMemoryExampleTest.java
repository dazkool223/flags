package dev.flagkit.usage.plain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.flagkit.FlagConfig;
import dev.flagkit.FlagDeclaration;
import dev.flagkit.InMemoryFlagStore;
import dev.flagkit.InvalidGraphException;
import org.junit.jupiter.api.Test;

class InMemoryExampleTest {

    @Test
    void theExampleProducesTheDocumentedOutput() {
        assertThat(InMemoryExample.run()).containsExactly(
                "ana -> enabled (ENABLED)",
                "bo -> enabled (ENABLED)",
                "cy -> disabled (PREREQUISITE_FAILED: premium-or-internal)",
                "di -> disabled (PREREQUISITE_FAILED: beta-region)",
                "",
                "why is cy disabled?",
                "  checkout = disabled (PREREQUISITE_FAILED: premium-or-internal)",
                "    beta-region = enabled (ENABLED)",
                "    premium-or-internal = disabled (PREREQUISITE_FAILED: premium-plan)",
                "      premium-plan = disabled (CONDITION_FALSE)",
                "      internal-staff = disabled (CONDITION_FALSE)");
    }

    @Test
    void aFlagThatIsOffIsDisabledWithoutRunningAnythingElse() {
        var store = new InMemoryFlagStore(FlagConfig.of("checkout").off().build());
        var evaluator = CheckoutFlags.evaluator(store);

        var result = evaluator.evaluate(CheckoutFlags.CHECKOUT, InMemoryExample.ANA);

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason().kind().name()).isEqualTo("SWITCH_OFF");
    }

    @Test
    void aDeclaredFlagThatIsNotInTheStoreIsDisabledSoCodeCanShipBeforeTheFlagExists() {
        var evaluator = CheckoutFlags.evaluator(new InMemoryFlagStore());

        var result = evaluator.evaluate(CheckoutFlags.CHECKOUT, InMemoryExample.ANA);

        assertThat(result.enabled()).isFalse();
        assertThat(result.reason().kind().name()).isEqualTo("FLAG_NOT_FOUND");
    }

    @Test
    void aPermanentFlagIsDeclaredWithJustAKey() {
        var killSwitch = new FlagDeclaration.PermanentFlag("payments-kill-switch");
        var evaluator = CheckoutFlags.evaluator(
                new InMemoryFlagStore(FlagConfig.of("payments-kill-switch").on().build()));

        assertThat(evaluator.evaluate(killSwitch, InMemoryExample.ANA).enabled()).isTrue();
    }

    @Test
    void loadingFailsLoudlyWhenTheStoreNamesAConditionNobodyRegistered() {
        var store = new InMemoryFlagStore(FlagConfig.of("checkout").on().condition("moon-phase").build());
        var evaluator = CheckoutFlags.evaluator(store);

        // A missing registration must never silently disable a feature.
        assertThatThrownBy(evaluator::load)
                .isInstanceOf(InvalidGraphException.class).hasMessageContaining("moon-phase");
    }

    @Test
    void loadingFailsLoudlyOnACycleOrADanglingPrerequisite() {
        var cycle = new InMemoryFlagStore(
                FlagConfig.of("a").on().requires("b").build(), FlagConfig.of("b").on().requires("a").build());
        var dangling = new InMemoryFlagStore(FlagConfig.of("a").on().requires("ghost").build());

        assertThatThrownBy(() -> CheckoutFlags.evaluator(cycle).load())
                .isInstanceOf(InvalidGraphException.class).hasMessageContaining("a -> b -> a");
        assertThatThrownBy(() -> CheckoutFlags.evaluator(dangling).load())
                .isInstanceOf(InvalidGraphException.class).hasMessageContaining("ghost");
    }
}
