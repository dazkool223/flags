package dev.flagkit;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The code-side handle for a flag: its key and kind. The kind affects how a flag is managed,
 * never how it is evaluated.
 */
public sealed interface FlagDeclaration {

    String key();

    /** A temporary flag guarding an unreleased feature. Must declare an expiry date. */
    record ReleaseFlag(String key, LocalDate expiry) implements FlagDeclaration {
        public ReleaseFlag {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(expiry, "expiry");
        }
    }

    /** A flag meant to stay, such as a kill switch or a plan entitlement. */
    record PermanentFlag(String key) implements FlagDeclaration {
        public PermanentFlag {
            Objects.requireNonNull(key, "key");
        }
    }
}
