package dev.flagkit;

import java.util.Objects;

/** Why an evaluation came out the way it did. {@code flagKey} is set for PREREQUISITE_FAILED. */
public record Reason(Kind kind, String flagKey) {

    public enum Kind { ENABLED, SWITCH_OFF, PREREQUISITE_FAILED, CONDITION_FALSE, FLAG_NOT_FOUND }

    public Reason {
        Objects.requireNonNull(kind, "kind");
    }

    public static Reason of(Kind kind) {
        return new Reason(kind, null);
    }
}
