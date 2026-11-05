package dev.flagkit.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the one decision point (the seam) for a feature. When the flag is enabled for the current
 * context the annotated method runs. Otherwise {@link #fallback()} runs with the same arguments.
 *
 * <p>The fallback is required, so a disabled flag never returns null by accident. It must be a
 * method in the same class (or a superclass) with the same parameter types and a return type the
 * seam's return type can hold. Startup fails if it is missing or incompatible, if the flag key
 * has no {@code FlagDeclaration} bean, or if there is no {@link FlagContextProvider} bean.
 *
 * <p><b>Proxy limit.</b> The check happens in a Spring proxy, so it only applies to calls that
 * come from another bean. A call from inside the same class ({@code this.method()}) skips the
 * proxy and always runs the annotated method. Put the seam on a method that other beans call.
 * The seam method must not be {@code private}, {@code final} or {@code static}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Flagged {

    /** The flag key. A {@code FlagDeclaration} bean with this key must exist. */
    String key();

    /** The name of the method to run when the flag is disabled. */
    String fallback();
}
