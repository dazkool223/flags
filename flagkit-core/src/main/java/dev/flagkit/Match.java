package dev.flagkit;

/** How a flag combines its prerequisites. */
public enum Match {
    /** Every prerequisite must be enabled. */
    ALL,
    /** At least one prerequisite must be enabled. */
    ANY
}
