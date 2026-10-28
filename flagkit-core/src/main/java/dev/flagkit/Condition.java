package dev.flagkit;

import java.util.Map;

/**
 * Evaluation logic written by the library user and attached to a flag by name. It receives the
 * evaluator's context and the flag's params, and knows nothing about other flags.
 */
public interface Condition<C> {

    /** The stable name the store refers to. Never a Java class name. */
    String name();

    boolean test(C context, Map<String, String> params);
}
