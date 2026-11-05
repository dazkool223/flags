package dev.flagkit.usage.plain;

import dev.flagkit.Evaluator;
import java.util.List;

/**
 * Where flags belong in application code: one decision point per feature, at the edge of the
 * feature. The flag is evaluated once here. The code below the seam receives the decision as a
 * plain value and never sees a flag, so removing the flag later means deleting one {@code if}
 * and the old branch.
 */
public final class CheckoutService {

    private final Evaluator<Customer> flags;

    public CheckoutService(Evaluator<Customer> flags) {
        this.flags = flags;
    }

    /** The seam. */
    public String checkout(Customer customer, List<String> cart) {
        if (flags.evaluate(CheckoutFlags.CHECKOUT, customer).enabled()) {
            return newCheckout(cart);
        }
        return legacyCheckout(cart);
    }

    private String newCheckout(List<String> cart) {
        return "new checkout: " + String.join(", ", cart);
    }

    private String legacyCheckout(List<String> cart) {
        return "legacy checkout: " + String.join(", ", cart);
    }
}
