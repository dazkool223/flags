package dev.flagkit;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A flag as held in the store. */
public record FlagConfig(String key, boolean on, Match match, String conditionName,
                         Map<String, String> params, List<String> prerequisites) {

    public FlagConfig {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(match, "match");
        params = Map.copyOf(params);
        prerequisites = List.copyOf(prerequisites);
    }

    public static Builder of(String key) {
        return new Builder(key);
    }

    public static final class Builder {
        private final String key;
        private boolean on;
        private Match match = Match.ALL;
        private String conditionName;
        private Map<String, String> params = Map.of();
        private List<String> prerequisites = List.of();

        private Builder(String key) {
            this.key = key;
        }

        public Builder on() {
            this.on = true;
            return this;
        }

        public Builder off() {
            this.on = false;
            return this;
        }

        public Builder match(Match match) {
            this.match = match;
            return this;
        }

        public Builder requires(String... prerequisiteKeys) {
            this.prerequisites = List.of(prerequisiteKeys);
            return this;
        }

        public Builder condition(String name) {
            return condition(name, Map.of());
        }

        public Builder condition(String name, Map<String, String> params) {
            this.conditionName = name;
            this.params = params;
            return this;
        }

        public FlagConfig build() {
            return new FlagConfig(key, on, match, conditionName, params, prerequisites);
        }
    }
}
