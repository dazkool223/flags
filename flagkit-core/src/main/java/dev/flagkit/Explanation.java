package dev.flagkit;

import java.util.List;
import java.util.Objects;

/** The full trace of one evaluation: the result and every flag visited to reach it. */
public record Explanation(Result result, Visit root) {

    public Explanation {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(root, "root");
    }

    /**
     * One flag visited during the walk. {@code prerequisites} holds the prerequisites that were
     * actually evaluated (evaluation stops at the first decisive one). {@code cached} is true
     * when the result was reused from an earlier visit in the same evaluation.
     */
    public record Visit(String flagKey, Result result, List<Visit> prerequisites, boolean cached) {
        public Visit {
            prerequisites = List.copyOf(prerequisites);
        }
    }
}
