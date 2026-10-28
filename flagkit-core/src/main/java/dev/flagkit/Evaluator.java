package dev.flagkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Walks the flag graph and runs each flag's condition. */
public final class Evaluator<C> {

    private final FlagStore source;
    private final Map<String, Condition<C>> conditions;
    private volatile Map<String, FlagConfig> snapshot;

    private Evaluator(FlagStore source, Map<String, Condition<C>> conditions) {
        this.source = source;
        this.conditions = conditions;
    }

    public static <C> Builder<C> builder(FlagStore source) {
        return new Builder<>(source);
    }

    public Result evaluate(FlagDeclaration flag, C context) {
        var graph = snapshot();
        if (!graph.containsKey(flag.key())) {
            return notFound();
        }
        return new Walk(context, graph, false).resolve(flag.key());
    }

    /** Evaluates like {@link #evaluate} and also returns every flag visited. Not for the hot path. */
    public Explanation explain(FlagDeclaration flag, C context) {
        var graph = snapshot();
        if (!graph.containsKey(flag.key())) {
            var result = notFound();
            return new Explanation(result, new Explanation.Visit(flag.key(), result, List.of(), false));
        }
        var walk = new Walk(context, graph, true);
        var result = walk.resolve(flag.key());
        return new Explanation(result, walk.rootVisit);
    }

    private static Result notFound() {
        return new Result(false, Reason.of(Reason.Kind.FLAG_NOT_FOUND));
    }

    /** Loads the graph now instead of on the first evaluation. Throws if the load fails. */
    public void load() {
        snapshot();
    }

    /**
     * Rebuilds the graph and swaps it in atomically. In-flight evaluations finish on the graph
     * they started with. If the new graph is invalid, the old one stays in place.
     */
    public synchronized void refresh() {
        snapshot = build();
    }

    /**
     * One top-level evaluation. Results are cached per walk, so a shared prerequisite runs once.
     * The walk uses an explicit stack, so prerequisite chains have no depth limit. When tracing,
     * each frame collects the visits of its prerequisites and {@code rootVisit} ends up holding
     * the whole tree.
     */
    private final class Walk {
        private final C context;
        private final Map<String, FlagConfig> graph;
        private final boolean tracing;
        private final Map<String, Result> memo = new HashMap<>();
        private Explanation.Visit rootVisit;

        Walk(C context, Map<String, FlagConfig> graph, boolean tracing) {
            this.context = context;
            this.graph = graph;
            this.tracing = tracing;
        }

        Result resolve(String rootKey) {
            var stack = new ArrayDeque<Frame>();
            stack.push(new Frame(rootKey, graph.get(rootKey), tracing));
            while (true) {
                var frame = stack.peek();
                var result = advance(frame, stack);
                if (result == null) {
                    continue;
                }
                stack.pop();
                memo.put(frame.key, result);
                var visit = tracing ? new Explanation.Visit(frame.key, result, frame.visits, false) : null;
                if (stack.isEmpty()) {
                    rootVisit = visit;
                    return result;
                }
                var parent = stack.peek();
                if (tracing) {
                    parent.visits.add(visit);
                }
                parent.accept(frame.key, result);
            }
        }

        /** Runs the frame until it is decided (returns the result) or needs a prerequisite (pushes it, returns null). */
        private Result advance(Frame frame, Deque<Frame> stack) {
            var flag = frame.flag;
            if (!frame.switchChecked) {
                frame.switchChecked = true;
                if (!flag.on()) {
                    return new Result(false, Reason.of(Reason.Kind.SWITCH_OFF));
                }
            }
            var prerequisites = flag.prerequisites();
            while (!frame.decided && frame.next < prerequisites.size()) {
                var key = prerequisites.get(frame.next);
                var known = memo.get(key);
                if (known == null) {
                    stack.push(new Frame(key, graph.get(key), tracing));
                    return null;
                }
                if (tracing) {
                    frame.visits.add(new Explanation.Visit(key, known, List.of(), true));
                }
                frame.accept(key, known);
            }
            var failed = frame.failedPrerequisite;
            if (!frame.decided && flag.match() == Match.ANY && !prerequisites.isEmpty()) {
                failed = prerequisites.get(0);
            }
            if (failed != null) {
                return new Result(false, new Reason(Reason.Kind.PREREQUISITE_FAILED, failed));
            }
            if (flag.conditionName() != null
                    && !conditions.get(flag.conditionName()).test(context, flag.params())) {
                return new Result(false, Reason.of(Reason.Kind.CONDITION_FALSE));
            }
            return new Result(true, Reason.of(Reason.Kind.ENABLED));
        }
    }

    /** A flag being resolved: how far through its prerequisites it has got. */
    private static final class Frame {
        final String key;
        final FlagConfig flag;
        final List<Explanation.Visit> visits;
        boolean switchChecked;
        int next;
        /** True once the prerequisites are settled early: ALL saw a disabled one, ANY an enabled one. */
        boolean decided;
        String failedPrerequisite;

        Frame(String key, FlagConfig flag, boolean tracing) {
            this.key = key;
            this.flag = flag;
            this.visits = tracing ? new ArrayList<>() : null;
        }

        void accept(String prerequisite, Result result) {
            next++;
            if (flag.match() == Match.ALL && !result.enabled()) {
                decided = true;
                failedPrerequisite = prerequisite;
            } else if (flag.match() == Match.ANY && result.enabled()) {
                decided = true;
            }
        }
    }

    private Map<String, FlagConfig> snapshot() {
        var current = snapshot;
        if (current == null) {
            synchronized (this) {
                current = snapshot;
                if (current == null) {
                    snapshot = current = build();
                }
            }
        }
        return current;
    }

    private Map<String, FlagConfig> build() {
        var graph = new LinkedHashMap<String, FlagConfig>();
        for (var flag : source.load()) {
            if (graph.put(flag.key(), flag) != null) {
                throw new InvalidGraphException("Duplicate flag key '" + flag.key() + "'");
            }
        }
        validate(graph);
        return Map.copyOf(graph);
    }

    private void validate(Map<String, FlagConfig> graph) {
        for (var flag : graph.values()) {
            if (flag.conditionName() != null && !conditions.containsKey(flag.conditionName())) {
                throw new InvalidGraphException("Flag '" + flag.key() + "' uses unknown condition '"
                        + flag.conditionName() + "'");
            }
            for (var prerequisite : flag.prerequisites()) {
                if (!graph.containsKey(prerequisite)) {
                    throw new InvalidGraphException("Flag '" + flag.key() + "' has prerequisite '"
                            + prerequisite + "' which does not exist");
                }
            }
        }
        var done = new HashSet<String>();
        for (var key : graph.keySet()) {
            findCycle(key, graph, done);
        }
    }

    /** Iterative depth-first search from {@code start}; {@code done} holds flags already proven acyclic. */
    private void findCycle(String start, Map<String, FlagConfig> graph, Set<String> done) {
        if (done.contains(start)) {
            return;
        }
        var path = new ArrayList<String>();
        var cursors = new ArrayList<Iterator<String>>();
        var onPath = new HashSet<String>();
        path.add(start);
        onPath.add(start);
        cursors.add(graph.get(start).prerequisites().iterator());
        while (!path.isEmpty()) {
            var last = path.size() - 1;
            var cursor = cursors.get(last);
            if (!cursor.hasNext()) {
                var finished = path.remove(last);
                cursors.remove(last);
                onPath.remove(finished);
                done.add(finished);
                continue;
            }
            var next = cursor.next();
            if (done.contains(next)) {
                continue;
            }
            if (onPath.contains(next)) {
                var cycle = new ArrayList<>(path.subList(path.indexOf(next), path.size()));
                cycle.add(next);
                throw new InvalidGraphException("Cycle in flag graph: " + String.join(" -> ", cycle));
            }
            path.add(next);
            onPath.add(next);
            cursors.add(graph.get(next).prerequisites().iterator());
        }
    }

    public static final class Builder<C> {
        private final FlagStore source;
        private final Map<String, Condition<C>> conditions = new HashMap<>();

        private Builder(FlagStore source) {
            this.source = Objects.requireNonNull(source, "source");
        }

        public Builder<C> condition(Condition<C> condition) {
            if (conditions.putIfAbsent(condition.name(), condition) != null) {
                throw new IllegalArgumentException("Condition already registered: " + condition.name());
            }
            return this;
        }

        public Evaluator<C> build() {
            return new Evaluator<>(source, Map.copyOf(conditions));
        }
    }
}
