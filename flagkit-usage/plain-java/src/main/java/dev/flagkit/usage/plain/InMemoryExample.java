package dev.flagkit.usage.plain;

import dev.flagkit.Evaluator;
import dev.flagkit.Explanation;
import dev.flagkit.Result;
import java.util.ArrayList;
import java.util.List;

/**
 * Core only, no database: the smallest complete use of flagkit. Run it with
 * {@code mvn -pl flagkit-usage/plain-java exec:java -Dexec.mainClass=dev.flagkit.usage.plain.InMemoryExample}
 * or from your IDE.
 *
 * <p>Steps: pick a store, register conditions, build the evaluator, ask about a declared flag.
 */
public final class InMemoryExample {

    static final Customer ANA = new Customer("ana", "eu", "premium", false);
    static final Customer BO = new Customer("bo", "eu", "free", true);
    static final Customer CY = new Customer("cy", "eu", "free", false);
    static final Customer DI = new Customer("di", "us", "premium", false);

    private InMemoryExample() {}

    public static void main(String[] args) {
        run().forEach(System.out::println);
    }

    static List<String> run() {
        // 1. A store holds the graph. InMemoryFlagStore is fixed at construction.
        // 2. The evaluator loads it on first use (call evaluator.load() to load it eagerly).
        Evaluator<Customer> evaluator = CheckoutFlags.evaluator(CheckoutFlags.inMemoryGraph());

        var lines = new ArrayList<String>();

        // 3. evaluate() answers "enabled?" and "why?" for one context. This is the hot path.
        for (var customer : List.of(ANA, BO, CY, DI)) {
            lines.add(customer.id() + " -> " + describe(evaluator.evaluate(CheckoutFlags.CHECKOUT, customer)));
        }

        // 4. explain() returns the whole trace. It is for debugging, not for the hot path.
        lines.add("");
        lines.add("why is cy disabled?");
        trace(evaluator.explain(CheckoutFlags.CHECKOUT, CY).root(), 1, lines);
        return lines;
    }

    static String describe(Result result) {
        var reason = result.reason();
        return (result.enabled() ? "enabled" : "disabled") + " (" + reason.kind()
                + (reason.flagKey() == null ? "" : ": " + reason.flagKey()) + ")";
    }

    private static void trace(Explanation.Visit visit, int depth, List<String> lines) {
        lines.add("  ".repeat(depth) + visit.flagKey() + " = " + describe(visit.result())
                + (visit.cached() ? " [cached]" : ""));
        visit.prerequisites().forEach(child -> trace(child, depth + 1, lines));
    }
}
