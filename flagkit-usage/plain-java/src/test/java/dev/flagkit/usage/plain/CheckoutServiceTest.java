package dev.flagkit.usage.plain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.flagkit.FlagConfig;
import dev.flagkit.InMemoryFlagStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * How to unit-test code that sits behind a flag. Build the evaluator over a tiny in-memory graph
 * that says exactly what the test needs. No database, no mocks.
 */
class CheckoutServiceTest {

    private static final List<String> CART = List.of("tea", "mug");

    private static CheckoutService serviceWith(FlagConfig... flags) {
        return new CheckoutService(CheckoutFlags.evaluator(new InMemoryFlagStore(flags)));
    }

    @Test
    void anEnabledFlagSendsTheCustomerThroughTheNewCheckout() {
        var service = serviceWith(FlagConfig.of("checkout").on().build());

        assertThat(service.checkout(InMemoryExample.CY, CART)).isEqualTo("new checkout: tea, mug");
    }

    @Test
    void aDisabledFlagKeepsTheLegacyCheckout() {
        var service = serviceWith(FlagConfig.of("checkout").off().build());

        assertThat(service.checkout(InMemoryExample.ANA, CART)).isEqualTo("legacy checkout: tea, mug");
    }

    @Test
    void theRealGraphDecidesPerCustomer() {
        var service = new CheckoutService(CheckoutFlags.evaluator(CheckoutFlags.inMemoryGraph()));

        assertThat(service.checkout(InMemoryExample.ANA, CART)).startsWith("new");
        assertThat(service.checkout(InMemoryExample.CY, CART)).startsWith("legacy");
        assertThat(service.checkout(InMemoryExample.DI, CART)).startsWith("legacy");
    }
}
