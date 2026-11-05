package dev.flagkit.spring;

/** A {@link Flagged} method or the setup around it is wrong. Thrown at startup. */
public final class FlagSeamException extends IllegalStateException {

    FlagSeamException(String message) {
        super(message);
    }
}
