package dev.flagkit.cli;

import dev.flagkit.FlagConfig;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Text output for the read commands: tree, single-flag details and the export file. */
final class Render {

    private Render() {}

    /**
     * Prints the graph with the flags nothing depends on as roots and prerequisites as children,
     * in stored order. Uses an explicit stack, so depth is not limited. A flag reached a second
     * time (a shared prerequisite) is printed once more without its children.
     */
    static String tree(List<FlagConfig> flags) {
        if (flags.isEmpty()) {
            return "(no flags)\n";
        }
        var byKey = flags.stream().collect(Collectors.toMap(FlagConfig::key, f -> f));
        var referenced = new HashSet<String>();
        flags.forEach(f -> referenced.addAll(f.prerequisites()));
        var roots = flags.stream().map(FlagConfig::key).filter(k -> !referenced.contains(k))
                .sorted().toList();

        var out = new StringBuilder();
        var shown = new HashSet<String>();
        var stack = new ArrayDeque<Item>();
        for (var i = roots.size() - 1; i >= 0; i--) {
            stack.push(new Item(roots.get(i), "", ""));
        }
        while (!stack.isEmpty()) {
            var item = stack.pop();
            var flag = byKey.get(item.key);
            var repeat = !shown.add(item.key);
            out.append(item.linePrefix).append(describe(flag));
            if (repeat && !flag.prerequisites().isEmpty()) {
                out.append(" (shown above)");
            }
            out.append('\n');
            if (repeat) {
                continue;
            }
            var children = flag.prerequisites();
            for (var i = children.size() - 1; i >= 0; i--) {
                var last = i == children.size() - 1;
                stack.push(new Item(children.get(i),
                        item.childPrefix + (last ? "`-- " : "|-- "),
                        item.childPrefix + (last ? "    " : "|   ")));
            }
        }
        return out.toString();
    }

    private record Item(String key, String linePrefix, String childPrefix) {}

    private static String describe(FlagConfig flag) {
        var line = new StringBuilder(flag.key()).append(" [").append(flag.on() ? "on" : "off")
                .append(", ").append(flag.match()).append(']');
        if (flag.conditionName() != null) {
            line.append(" condition=").append(flag.conditionName());
        }
        if (!flag.params().isEmpty()) {
            line.append(" {").append(new TreeMap<>(flag.params()).entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "))).append('}');
        }
        return line.toString();
    }

    static String show(FlagConfig flag) {
        var out = new StringBuilder();
        out.append("key: ").append(flag.key()).append('\n');
        out.append("switch: ").append(flag.on() ? "on" : "off").append('\n');
        out.append("match: ").append(flag.match()).append('\n');
        out.append("condition: ").append(flag.conditionName() == null ? "(none)" : flag.conditionName()).append('\n');
        out.append("params:").append(flag.params().isEmpty() ? " (none)" : "").append('\n');
        new TreeMap<>(flag.params()).forEach((k, v) -> out.append("  ").append(k).append(" = ").append(v).append('\n'));
        out.append("prerequisites:").append(flag.prerequisites().isEmpty() ? " (none)" : "").append('\n');
        flag.prerequisites().forEach(p -> out.append("  ").append(p).append('\n'));
        return out.toString();
    }

    /**
     * One environment's graph as JSON. Flags are sorted by key, params by name, and nothing
     * time-dependent is included, so two exports of the same graph are byte-identical and diff
     * cleanly. Prerequisites keep their stored order because it affects evaluation. Lines end in
     * "\n" on every platform.
     */
    static String export(List<FlagConfig> flags) {
        var sorted = flags.stream().sorted(Comparator.comparing(FlagConfig::key)).toList();
        var out = new StringBuilder("{\n  \"flags\": [");
        for (var i = 0; i < sorted.size(); i++) {
            var flag = sorted.get(i);
            out.append(i == 0 ? "\n" : ",\n");
            out.append("    {\n");
            out.append("      \"key\": ").append(quote(flag.key())).append(",\n");
            out.append("      \"switch\": ").append(flag.on()).append(",\n");
            out.append("      \"match\": ").append(quote(flag.match().name())).append(",\n");
            out.append("      \"condition\": ")
                    .append(flag.conditionName() == null ? "null" : quote(flag.conditionName())).append(",\n");
            out.append("      \"params\": {");
            var params = new TreeMap<>(flag.params());
            var first = true;
            for (Map.Entry<String, String> param : params.entrySet()) {
                out.append(first ? "\n" : ",\n").append("        ").append(quote(param.getKey()))
                        .append(": ").append(quote(param.getValue()));
                first = false;
            }
            out.append(params.isEmpty() ? "},\n" : "\n      },\n");
            out.append("      \"prerequisites\": [")
                    .append(flag.prerequisites().stream().map(Render::quote).collect(Collectors.joining(", ")))
                    .append("]\n");
            out.append("    }");
        }
        out.append(sorted.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
        return out.toString();
    }

    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (var ch : value.toCharArray()) {
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
