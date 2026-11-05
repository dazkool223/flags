package dev.flagkit.usage.plain;

import dev.flagkit.Evaluator;
import dev.flagkit.FlagConfig;
import dev.flagkit.FlagDeclaration;
import dev.flagkit.FlagStore;
import dev.flagkit.InMemoryFlagStore;
import dev.flagkit.Match;
import java.time.LocalDate;
import java.util.Map;

/**
 * The flag setup used by every example: "the new checkout applies only in beta regions, and only
 * for premium customers or internal staff".
 *
 * <pre>
 * checkout                      ALL of
 * |-- beta-region               condition region-in {regions=eu,uk}
 * `-- premium-or-internal       ANY of
 *     |-- premium-plan          condition plan-is {plan=premium}
 *     `-- internal-staff        condition staff
 * </pre>
 */
public final class CheckoutFlags {

    private CheckoutFlags() {}

    /**
     * What the code declares. Only the flag the code asks about needs a declaration. The inner
     * nodes (beta-region and the rest) live in the store, and code never mentions them.
     *
     * <p>A release flag is temporary and must carry an expiry date. A permanent flag (a kill
     * switch, a plan entitlement) is {@code new FlagDeclaration.PermanentFlag("key")}.
     */
    public static final FlagDeclaration CHECKOUT =
            new FlagDeclaration.ReleaseFlag("checkout", LocalDate.of(2027, 3, 31));

    /** Registers every condition the store may refer to. Loading fails if the store names another. */
    public static Evaluator<Customer> evaluator(FlagStore store) {
        return Evaluator.<Customer>builder(store)
                .condition(new Conditions.RegionIn())
                .condition(new Conditions.PlanIs())
                .condition(new Conditions.Staff())
                .build();
    }

    /**
     * The graph above as a fixed in-memory store. Use it in unit tests and demos. Real
     * environments keep the graph in a database (see {@link PostgresExample}).
     */
    public static InMemoryFlagStore inMemoryGraph() {
        return new InMemoryFlagStore(
                FlagConfig.of("checkout").on().match(Match.ALL)
                        .requires("beta-region", "premium-or-internal").build(),
                FlagConfig.of("beta-region").on()
                        .condition("region-in", Map.of("regions", "eu,uk")).build(),
                FlagConfig.of("premium-or-internal").on().match(Match.ANY)
                        .requires("premium-plan", "internal-staff").build(),
                FlagConfig.of("premium-plan").on()
                        .condition("plan-is", Map.of("plan", "premium")).build(),
                FlagConfig.of("internal-staff").on()
                        .condition("staff").build());
    }
}
