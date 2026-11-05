package dev.flagkit.usage.spring;

import dev.flagkit.spring.Flagged;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The seam: one annotated method per feature. The annotation replaces the {@code if} from the
 * plain Java example. Code below this method never sees a flag.
 *
 * <p>The fallback is required, has the same parameters, and runs when the flag is disabled.
 * Startup fails if it is missing or has a different signature.
 *
 * <p>Calls come through a Spring proxy, so only calls from other beans are guarded. A call to
 * {@code checkout(...)} from inside this class would skip the flag.
 */
@Service
public class CheckoutService {

    @Flagged(key = "checkout", fallback = "legacyCheckout")
    public String checkout(List<String> cart) {
        return "new checkout: " + String.join(", ", cart);
    }

    String legacyCheckout(List<String> cart) {
        return "legacy checkout: " + String.join(", ", cart);
    }
}
