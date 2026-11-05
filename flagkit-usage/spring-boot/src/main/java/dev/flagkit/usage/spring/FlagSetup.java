package dev.flagkit.usage.spring;

import dev.flagkit.FlagDeclaration;
import java.time.LocalDate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two beans flagkit-spring needs from the application besides the conditions: the flags the
 * code uses, and the context provider.
 */
@Configuration(proxyBeanMethods = false)
class FlagSetup {

    /**
     * One bean per flag the code asks about. {@code @Flagged(key = "checkout")} is checked
     * against these at startup. A release flag carries an expiry date, a permanent flag does not.
     * Inner nodes of the graph (beta-region and so on) live in the store and need no declaration.
     */
    @Bean
    FlagDeclaration checkoutFlag() {
        return new FlagDeclaration.ReleaseFlag("checkout", LocalDate.of(2027, 3, 31));
    }

    /**
     * Exactly one provider. flagkit calls it once per guarded method call, so annotated methods
     * need no extra parameter.
     */
    @Bean
    dev.flagkit.spring.FlagContextProvider<Customer> flagContext(CurrentCustomer currentCustomer) {
        return currentCustomer::get;
    }
}
