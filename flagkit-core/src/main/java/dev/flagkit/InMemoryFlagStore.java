package dev.flagkit;

import java.util.List;

/** A fixed graph held in memory, for unit tests that need no database. */
public final class InMemoryFlagStore implements FlagStore {

    private final List<FlagConfig> flags;

    public InMemoryFlagStore(FlagConfig... flags) {
        this.flags = List.of(flags);
    }

    @Override
    public List<FlagConfig> load() {
        return flags;
    }
}
