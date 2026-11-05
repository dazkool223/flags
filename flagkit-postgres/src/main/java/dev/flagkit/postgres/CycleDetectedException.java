package dev.flagkit.postgres;

import java.util.List;

/** Adding a prerequisite was rejected because it would create a cycle. */
public final class CycleDetectedException extends FlagWriteException {

    private final List<String> path;

    CycleDetectedException(List<String> path) {
        super("Adding this prerequisite would create a cycle: " + String.join(" -> ", path));
        this.path = List.copyOf(path);
    }

    /** The cycle as it would exist, first and last entry being the flag being edited. */
    public List<String> path() {
        return path;
    }
}
