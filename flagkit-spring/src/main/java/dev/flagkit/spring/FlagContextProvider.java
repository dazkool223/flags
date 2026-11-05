package dev.flagkit.spring;

/**
 * Supplies the evaluation context for {@link Flagged} methods, so seams need no extra parameter.
 * Define exactly one bean of this type. Typical implementations read the current user or tenant
 * from a request-scoped holder or a security context. Every {@code Condition} bean registered
 * with the evaluator must accept the type this provider returns.
 */
@FunctionalInterface
public interface FlagContextProvider<C> {

    /** The context for the call being made right now. Called once per guarded method call. */
    C current();
}
