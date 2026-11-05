package dev.flagkit.usage.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flagkit.Evaluator;
import dev.flagkit.FlagStore;
import dev.flagkit.InMemoryFlagStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** The default profile: in-memory graph, no database, the whole app started for real. */
class DemoProfileTest {

    private static final List<String> CART = List.of("tea", "mug");

    private static org.springframework.context.ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(UsageApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off")
                .run();
    }

    @Test
    void theAnnotatedMethodRunsWhenTheFlagIsEnabledForTheCurrentCustomer() {
        try (var context = start()) {
            var checkout = context.getBean(CheckoutService.class);
            var current = context.getBean(CurrentCustomer.class);

            current.set(new Customer("ana", "eu", "premium", false));
            assertThat(checkout.checkout(CART)).isEqualTo("new checkout: tea, mug");

            current.set(new Customer("bo", "eu", "free", true));
            assertThat(checkout.checkout(CART)).isEqualTo("new checkout: tea, mug");
        }
    }

    @Test
    void theFallbackRunsWhenTheFlagIsDisabled() {
        try (var context = start()) {
            var checkout = context.getBean(CheckoutService.class);
            var current = context.getBean(CurrentCustomer.class);

            current.set(new Customer("cy", "eu", "free", false));
            assertThat(checkout.checkout(CART)).isEqualTo("legacy checkout: tea, mug");

            current.set(new Customer("di", "us", "premium", false));
            assertThat(checkout.checkout(CART)).isEqualTo("legacy checkout: tea, mug");
        }
    }

    @Test
    void theAutoConfiguredEvaluatorAndTheApplicationsOwnStoreAreAvailable() {
        try (var context = start()) {
            assertThat(context.getBean(FlagStore.class)).isInstanceOf(InMemoryFlagStore.class);
            assertThat(context.getBean(Evaluator.class)).isNotNull();
        }
    }
}
