package dev.flagkit.postgres;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The name prefix of one environment's flag tables. Validated on construction, before any SQL is
 * built, so a configuration value can never inject SQL. An empty prefix is allowed.
 */
public final class TablePrefix {

    private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

    private final String value;

    private TablePrefix(String value) {
        this.value = value;
    }

    public static TablePrefix of(String value) {
        Objects.requireNonNull(value, "prefix");
        if (!value.isEmpty() && !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Invalid table prefix: it must be empty or match " + VALID.pattern());
        }
        return new TablePrefix(value);
    }

    public String value() {
        return value;
    }

    /** The full name of one of the environment's tables, for example {@code prod_flag}. */
    String table(String name) {
        return value + name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TablePrefix that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
