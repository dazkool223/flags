package dev.flagkit;

import java.util.Objects;

public record Result(boolean enabled, Reason reason) {

    public Result {
        Objects.requireNonNull(reason, "reason");
    }
}
