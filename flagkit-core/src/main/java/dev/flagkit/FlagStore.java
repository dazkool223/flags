package dev.flagkit;

import java.util.List;

/** Supplies the whole flag graph for one environment. */
public interface FlagStore {

    List<FlagConfig> load();
}
