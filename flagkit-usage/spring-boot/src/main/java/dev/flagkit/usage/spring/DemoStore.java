package dev.flagkit.usage.spring;

import dev.flagkit.FlagConfig;
import dev.flagkit.FlagStore;
import dev.flagkit.InMemoryFlagStore;
import dev.flagkit.Match;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Store for the {@code demo} profile (the default): a fixed in-memory graph, no database.
 * flagkit-spring backs off its own Postgres store when the application defines a
 * {@link FlagStore} bean. The same pattern works in tests of your own application.
 *
 * <pre>
 * checkout                  ALL of
 * |-- beta-region           region-in {regions=eu,uk}
 * `-- premium-or-internal   ANY of
 *     |-- premium-plan      plan-is {plan=premium}
 *     `-- internal-staff    staff
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
class DemoStore {

    @Bean
    FlagStore demoFlagStore() {
        return new InMemoryFlagStore(
                FlagConfig.of("checkout").on().match(Match.ALL)
                        .requires("beta-region", "premium-or-internal").build(),
                FlagConfig.of("beta-region").on().condition("region-in", Map.of("regions", "eu,uk")).build(),
                FlagConfig.of("premium-or-internal").on().match(Match.ANY)
                        .requires("premium-plan", "internal-staff").build(),
                FlagConfig.of("premium-plan").on().condition("plan-is", Map.of("plan", "premium")).build(),
                FlagConfig.of("internal-staff").on().condition("staff").build());
    }
}
