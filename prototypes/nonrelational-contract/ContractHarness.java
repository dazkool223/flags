// PROTOTYPE, throwaway. Tests docs/non-relational-stores.md against store CLASSES, not products.
// Each store below is an in-memory model of a stated guarantee. A pass or fail shows what follows
// from that guarantee. It does not show that a named product behaves this way.
//
// Run from the repo root:
//   javac -d target-proto flagkit-core/src/main/java/dev/flagkit/*.java
//   java -cp target-proto prototypes/nonrelational-contract/ContractHarness.java

import dev.flagkit.Evaluator;
import dev.flagkit.FlagConfig;
import dev.flagkit.FlagStore;
import dev.flagkit.InvalidGraphException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.regex.Pattern;

public class ContractHarness {

    // --- domain ------------------------------------------------------------------------------

    record Flag(String key, boolean on, List<String> pre) {}
    record Change(Map<String, Flag> upserts, Set<String> removes) {}
    record Audit(String writeId, String op, long version, Change change) {}
    record Snap(Map<String, Flag> graph, long version) {}

    static class Rejected extends Exception { Rejected(String m) { super(m, null, false, false); } }
    static class Conflict extends RuntimeException { Conflict() { super(null, null, false, false); } }
    static class Unknown extends RuntimeException { Unknown() { super(null, null, false, false); } }
    static class Crash extends RuntimeException { Crash() { super(null, null, false, false); } }

    static Map<String, Flag> applied(Map<String, Flag> graph, Change c) {
        var next = new TreeMap<>(graph);
        next.putAll(c.upserts());
        c.removes().forEach(next::remove);
        return next;
    }

    /** The pure rules from section 1: apply(graph, operation) gives a change or a rejection. */
    interface Op { Change apply(Map<String, Flag> g) throws Rejected; }

    record Create(String k) implements Op {
        public Change apply(Map<String, Flag> g) throws Rejected {
            if (g.containsKey(k)) throw new Rejected("exists");
            return new Change(Map.of(k, new Flag(k, false, List.of())), Set.of());
        }
    }
    record Delete(String k) implements Op {
        public Change apply(Map<String, Flag> g) throws Rejected {
            if (!g.containsKey(k)) throw new Rejected("missing");
            for (var f : g.values()) if (f.pre().contains(k)) throw new Rejected("has dependants");
            return new Change(Map.of(), Set.of(k));
        }
    }
    record Switch(String k, boolean on) implements Op {
        public Change apply(Map<String, Flag> g) throws Rejected {
            var f = g.get(k);
            if (f == null) throw new Rejected("missing");
            return new Change(Map.of(k, new Flag(k, on, f.pre())), Set.of());
        }
    }
    record AddPre(String k, String p) implements Op {
        public Change apply(Map<String, Flag> g) throws Rejected {
            var f = g.get(k);
            if (f == null || !g.containsKey(p)) throw new Rejected("missing");
            if (k.equals(p) || f.pre().contains(p)) throw new Rejected("already");
            if (reaches(g, p, k)) throw new Rejected("cycle");
            var pre = new ArrayList<>(f.pre());
            pre.add(p);
            return new Change(Map.of(k, new Flag(k, f.on(), List.copyOf(pre))), Set.of());
        }
    }
    record RemovePre(String k, String p) implements Op {
        public Change apply(Map<String, Flag> g) throws Rejected {
            var f = g.get(k);
            if (f == null || !f.pre().contains(p)) throw new Rejected("not a prerequisite");
            var pre = new ArrayList<>(f.pre());
            pre.remove(p);
            return new Change(Map.of(k, new Flag(k, f.on(), List.copyOf(pre))), Set.of());
        }
    }
    /** Touches two flags. FlagWriter has no such operation today; import and promotion would. */
    record SetBoth(String a, String b, boolean on) implements Op {
        public Change apply(Map<String, Flag> g) {
            return new Change(Map.of(a, new Flag(a, on, g.get(a).pre()), b, new Flag(b, on, g.get(b).pre())), Set.of());
        }
    }

    static boolean reaches(Map<String, Flag> g, String from, String to) {
        var seen = new HashSet<String>();
        var stack = new ArrayDeque<String>();
        stack.push(from);
        while (!stack.isEmpty()) {
            var n = stack.pop();
            if (n.equals(to)) return true;
            if (!seen.add(n)) continue;
            var f = g.get(n);
            if (f != null) f.pre().forEach(stack::push);
        }
        return false;
    }
    static int danglingCount(Map<String, Flag> g) {
        int n = 0;
        for (var f : g.values()) for (var p : f.pre()) if (!g.containsKey(p)) n++;
        return n;
    }
    static boolean hasCycle(Map<String, Flag> g) {
        for (var f : g.values()) for (var p : f.pre()) if (reaches(g, p, f.key())) return true;
        return false;
    }

    static void pause(long ns) { if (ns > 0) LockSupport.parkNanos(ns); }
    static ThreadLocalRandom r() { return ThreadLocalRandom.current(); }
    static final ThreadLocal<Integer> PIN = ThreadLocal.withInitial(() -> 0);

    // --- the store contract from section 2 ---------------------------------------------------

    interface Store {
        Snap read();
        void commit(long expected, Change c, String writeId, String op);
        Snap truth();
        List<Audit> audit();
        boolean hasWriteId(String id);
        default void finish() {}
    }

    /** Class: atomic multi-key transaction guarded by a version, plus snapshot reads. */
    static class TxnSnapshot implements Store {
        Map<String, Flag> g = new TreeMap<>();
        long v;
        List<Audit> log = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        public synchronized Snap read() { return new Snap(new TreeMap<>(g), v); }
        public synchronized void commit(long e, Change c, String id, String op) {
            if (v != e) throw new Conflict();
            land(c, id, op);
        }
        void land(Change c, String id, String op) {
            g = applied(g, c);
            v++;
            log.add(new Audit(id, op, v, c));
            ids.add(id);
        }
        public synchronized Snap truth() { return new Snap(new TreeMap<>(g), v); }
        public synchronized List<Audit> audit() { return new ArrayList<>(log); }
        public synchronized boolean hasWriteId(String id) { return ids.contains(id); }
    }

    /** Positive control: no compare at all. The checks below must catch this or they prove nothing. */
    static class NoGuardControl extends TxnSnapshot {
        @Override public synchronized void commit(long e, Change c, String id, String op) { land(c, id, op); }
    }

    /** Class: single-key CAS, one document holding the graph and its audit entries. */
    static class SingleDocEmbedded extends TxnSnapshot {}

    /** Class: single-key CAS, graph document with audit in a separate key. Two steps, not one. */
    static class SingleDocSeparateAudit extends TxnSnapshot {
        final double crashP;
        SingleDocSeparateAudit(double crashP) { this.crashP = crashP; }
        @Override public void commit(long e, Change c, String id, String op) {
            synchronized (this) {
                if (v != e) throw new Conflict();
                g = applied(g, c);
                v++;
            }
            if (r().nextDouble() < crashP) throw new Crash();
            synchronized (this) {
                log.add(new Audit(id, op, e + 1, c));
                ids.add(id);
            }
        }
    }

    /** Class: put-if-absent objects and a list call (object stores). Each version holds graph and audit. */
    static class CreateOnlyLog implements Store {
        record Obj(Map<String, Flag> g, Audit a, boolean deleteMarker) {}
        final NavigableMap<Long, Deque<Obj>> objects = new TreeMap<>();
        synchronized long highest() {
            for (var e : objects.descendingMap().entrySet()) if (!e.getValue().peek().deleteMarker()) return e.getKey();
            return 0;
        }
        public synchronized Snap read() {
            long h = highest();
            return new Snap(h == 0 ? new TreeMap<>() : objects.get(h).peek().g(), h);
        }
        public synchronized void commit(long e, Change c, String id, String op) {
            var cur = objects.get(e + 1);
            if (cur != null && !cur.peek().deleteMarker()) throw new Conflict(); // If-None-Match semantics
            var base = e == 0 ? new TreeMap<String, Flag>() : objects.get(e).peek().g();
            objects.computeIfAbsent(e + 1, k -> new ArrayDeque<>())
                    .push(new Obj(applied(base, c), new Audit(id, op, e + 1, c), false));
        }
        /** A plain delete in a versioned bucket: hides the object, keeps the locked version. */
        synchronized void hideHighest() { objects.get(highest()).push(new Obj(null, null, true)); }
        synchronized int reusedVersions() {
            int n = 0;
            for (var d : objects.values()) if (d.stream().filter(o -> !o.deleteMarker()).count() > 1) n++;
            return n;
        }
        public Snap truth() { return read(); }
        public synchronized List<Audit> audit() {
            var out = new ArrayList<Audit>();
            for (var d : objects.values()) if (!d.peek().deleteMarker()) out.add(d.peek().a());
            return out;
        }
        public synchronized boolean hasWriteId(String id) {
            return audit().stream().anyMatch(a -> a.writeId().equals(id));
        }
    }

    /**
     * Class: atomic commit, but other readers can see its items land one at a time, and a scan is
     * many separate reads. Read uses the doc's sandwich: meta, scan, meta, retry if it moved.
     */
    static class TxnNoSnapshot implements Store {
        final ConcurrentHashMap<String, Flag> g = new ConcurrentHashMap<>();
        volatile long meta;
        final ReentrantLock writer = new ReentrantLock();
        final List<Audit> log = Collections.synchronizedList(new ArrayList<>());
        final Set<String> ids = ConcurrentHashMap.newKeySet();
        public Snap read() {
            while (true) {
                long m1 = meta;
                var out = new TreeMap<String, Flag>();
                int scanned = 0;
                for (var k : new TreeSet<>(g.keySet())) {
                    var f = g.get(k);
                    if (f != null) out.put(k, f);
                    if (++scanned % 8 == 0) pause(20_000); // one result page
                }
                if (meta == m1) return new Snap(out, m1);
            }
        }
        public void commit(long e, Change c, String id, String op) {
            writer.lock();
            try {
                if (meta != e) throw new Conflict();
                var steps = new ArrayList<Runnable>();
                c.upserts().forEach((k, f) -> steps.add(() -> g.put(k, f)));
                c.removes().forEach(k -> steps.add(() -> g.remove(k)));
                steps.add(() -> meta = e + 1);
                Collections.shuffle(steps);
                for (var s : steps) { s.run(); pause(50_000); }
                log.add(new Audit(id, op, e + 1, c));
                ids.add(id);
            } finally {
                writer.unlock();
            }
        }
        public Snap truth() { return new Snap(new TreeMap<>(g), meta); }
        public List<Audit> audit() { synchronized (log) { return new ArrayList<>(log); } }
        public boolean hasWriteId(String id) { return ids.contains(id); }
    }

    /**
     * Class: only single-key CAS, used with the doc's one-key-per-flag layout. The guard and the
     * flag cannot land together, so the guard moves first and the flag follows.
     */
    static class SplitGuardNoTxn implements Store {
        final ConcurrentHashMap<String, Flag> g = new ConcurrentHashMap<>();
        final AtomicLong meta = new AtomicLong();
        final List<Audit> log = Collections.synchronizedList(new ArrayList<>());
        final Set<String> ids = ConcurrentHashMap.newKeySet();
        final double crashP;
        SplitGuardNoTxn(double crashP) { this.crashP = crashP; }
        public Snap read() {
            while (true) {
                long m1 = meta.get();
                var out = new TreeMap<String, Flag>(g);
                if (meta.get() == m1) return new Snap(out, m1);
            }
        }
        public void commit(long e, Change c, String id, String op) {
            if (!meta.compareAndSet(e, e + 1)) throw new Conflict();
            pause(100_000); // one network round trip between the two writes
            if (r().nextDouble() < crashP) throw new Crash();
            g.putAll(c.upserts());
            c.removes().forEach(g::remove);
            log.add(new Audit(id, op, e + 1, c));
            ids.add(id);
        }
        public Snap truth() { return new Snap(new TreeMap<>(g), meta.get()); }
        public List<Audit> audit() { synchronized (log) { return new ArrayList<>(log); } }
        public boolean hasWriteId(String id) { return ids.contains(id); }
    }

    /** Class: linearizable writes on a leader; reads may come from a lagging follower. */
    static class StaleFollowerReads extends TxnSnapshot {
        final Deque<Snap> history = new ArrayDeque<>();
        @Override synchronized void land(Change c, String id, String op) {
            history.addFirst(new Snap(new TreeMap<>(g), v));
            if (history.size() > 6) history.removeLast();
            super.land(c, id, op);
        }
        @Override public synchronized Snap read() {
            if (history.isEmpty() || r().nextBoolean()) return truth();
            return history.stream().skip(r().nextInt(history.size())).findFirst().orElseThrow();
        }
    }

    /** Class: CAS on a primary, replicated asynchronously, or synchronously for the control. */
    static class FailoverStore extends TxnSnapshot {
        final boolean sync;
        volatile int replicated;
        final Thread replicator;
        volatile boolean stop;
        int reusedVersions;
        final Set<Long> everUsed = new HashSet<>();
        FailoverStore(boolean sync) {
            this.sync = sync;
            replicator = new Thread(() -> {
                while (!stop) {
                    pause(2_000_000);
                    synchronized (this) { if (replicated < log.size()) replicated++; }
                }
            });
            replicator.setDaemon(true);
            replicator.start();
        }
        @Override void land(Change c, String id, String op) {
            super.land(c, id, op);
            if (!everUsed.add(v)) reusedVersions++;
            if (sync) replicated = log.size();
        }
        synchronized void failover() {
            log = new ArrayList<>(log.subList(0, replicated));
            ids = new HashSet<>();
            g = new TreeMap<>();
            for (var a : log) { g = applied(g, a.change()); ids.add(a.writeId()); }
            v = log.size();
        }
        @Override public void finish() { stop = true; }
    }

    /** Class: each replica checks its own copy and accepts; replicas merge later, last write wins. */
    static class LastWriteWins implements Store {
        final boolean wholeDoc;
        static final AtomicLong CLOCK = new AtomicLong();
        record Stamped(Flag f, long ts) {}
        class Replica {
            final Map<String, Stamped> keys = new HashMap<>();
            long v, docTs;
            final Map<String, Audit> audit = new HashMap<>();
        }
        final Replica[] rs = { new Replica(), new Replica() };
        final Thread syncer;
        volatile boolean stop;
        LastWriteWins(boolean wholeDoc) {
            this.wholeDoc = wholeDoc;
            syncer = new Thread(() -> { while (!stop) { pause(3_000_000); sync(); } });
            syncer.setDaemon(true);
            syncer.start();
        }
        synchronized void sync() {
            var a = rs[0];
            var b = rs[1];
            if (wholeDoc) {
                if (a.docTs == b.docTs) return;
                var win = a.docTs > b.docTs ? a : b;
                var lose = win == a ? b : a;
                lose.keys.clear();
                lose.keys.putAll(win.keys);
                lose.v = win.v;
                lose.docTs = win.docTs;
                lose.audit.clear();
                lose.audit.putAll(win.audit);
                return;
            }
            for (var from : rs) {
                var to = from == a ? b : a;
                from.keys.forEach((k, s) -> to.keys.merge(k, s, (x, y) -> x.ts() >= y.ts() ? x : y));
                to.audit.putAll(from.audit);
            }
            long max = Math.max(a.v, b.v);
            a.v = b.v = max;
        }
        static Map<String, Flag> graph(Replica r) {
            var out = new TreeMap<String, Flag>();
            r.keys.forEach((k, s) -> { if (s.f() != null) out.put(k, s.f()); });
            return out;
        }
        public synchronized Snap read() { var r = rs[PIN.get() % 2]; return new Snap(graph(r), r.v); }
        public synchronized void commit(long e, Change c, String id, String op) {
            var r = rs[PIN.get() % 2];
            if (r.v != e) throw new Conflict();
            long ts = CLOCK.incrementAndGet();
            c.upserts().forEach((k, f) -> r.keys.put(k, new Stamped(f, ts)));
            c.removes().forEach(k -> r.keys.put(k, new Stamped(null, ts)));
            r.v = e + 1;
            r.docTs = ts;
            r.audit.put(id, new Audit(id, op, e + 1, c));
        }
        public synchronized Snap truth() { sync(); return new Snap(graph(rs[0]), rs[0].v); }
        public synchronized List<Audit> audit() { sync(); return new ArrayList<>(rs[0].audit.values()); }
        public synchronized boolean hasWriteId(String id) { return rs[PIN.get() % 2].audit.containsKey(id); }
        @Override public void finish() { stop = true; }
    }

    /** Fault: the response is lost. The write landed, did not land, lands late, or a resend reports a conflict. */
    static class LostAck implements Store {
        final Store d;
        final double p;
        final ExecutorService late = Executors.newCachedThreadPool(t -> { var x = new Thread(t); x.setDaemon(true); return x; });
        LostAck(Store d, double p) { this.d = d; this.p = p; }
        public Snap read() { return d.read(); }
        public void commit(long e, Change c, String id, String op) {
            if (r().nextDouble() >= p) { d.commit(e, c, id, op); return; }
            switch (r().nextInt(4)) {
                case 0 -> { d.commit(e, c, id, op); throw new Unknown(); }
                case 1 -> throw new Unknown();
                case 2 -> {
                    long wait = r().nextLong(5_000_000);
                    late.submit(() -> { pause(wait); try { d.commit(e, c, id, op); } catch (Conflict ignored) {} });
                    throw new Unknown();
                }
                default -> { d.commit(e, c, id, op); throw new Conflict(); } // transport resent; second send lost the CAS
            }
        }
        public Snap truth() { return d.truth(); }
        public List<Audit> audit() { return d.audit(); }
        public boolean hasWriteId(String id) { return d.hasWriteId(id); }
        @Override public void finish() {
            late.shutdown();
            try { late.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            d.finish();
        }
    }

    /** Fault: the client dies right after an otherwise atomic commit. */
    static class CrashAfterCommit extends TxnSnapshot {
        final double p;
        CrashAfterCommit(double p) { this.p = p; }
        @Override public void commit(long e, Change c, String id, String op) {
            super.commit(e, c, id, op);
            if (r().nextDouble() < p) throw new Crash();
        }
    }

    // --- the writer loop from section 2 ------------------------------------------------------

    enum Outcome { ACK, REJECTED, GAVE_UP, UNKNOWN, CRASHED }

    /**
     * DOC_LOOP: exactly the doc, retry on ConflictException only; anything else surfaces as an error.
     * NAIVE_RETRY: the same, but a timeout is also retried, which is what a client or an operator does.
     * WRITE_ID: the proposed fix, before every retry look for this write's ID in the audit trail.
     */
    enum Policy { DOC_LOOP, NAIVE_RETRY, WRITE_ID }

    record Rec(String id, Op op, Outcome outcome) {}

    static class Writer {
        final Store s; final Policy p; final int max; final long window;
        final AtomicLong attempts = new AtomicLong();
        final Queue<Rec> ledger = new ConcurrentLinkedQueue<>();
        Writer(Store s, Policy p, int max, long window) { this.s = s; this.p = p; this.max = max; this.window = window; }
        Outcome exec(String id, Op op) {
            var o = run(id, op);
            ledger.add(new Rec(id, op, o));
            return o;
        }
        private Outcome run(String id, Op op) {
            boolean ambiguous = false;
            for (int a = 0; a < max; a++) {
                attempts.incrementAndGet();
                var snap = s.read();
                if (p == Policy.WRITE_ID && a > 0 && s.hasWriteId(id)) return Outcome.ACK;
                Change c;
                try { c = op.apply(snap.graph()); } catch (Rejected e) { return Outcome.REJECTED; }
                pause(r().nextLong(window + 1));
                try {
                    s.commit(snap.version(), c, id, op.toString());
                    return Outcome.ACK;
                } catch (Conflict e) {
                    pause(r().nextLong(200_000L << Math.min(a, 5)));
                } catch (Unknown e) {
                    ambiguous = true;
                    if (p == Policy.DOC_LOOP) return Outcome.UNKNOWN;
                    pause(r().nextLong(200_000L << Math.min(a, 5)));
                } catch (Crash e) {
                    return Outcome.CRASHED;
                }
            }
            return ambiguous ? Outcome.UNKNOWN : Outcome.GAVE_UP;
        }
    }

    // --- invariants --------------------------------------------------------------------------

    static Map<String, Integer> check(Store s, Collection<Rec> ledger) {
        var v = new LinkedHashMap<String, Integer>();
        var t = s.truth();
        var audit = s.audit();
        if (hasCycle(t.graph())) v.put("final graph has a cycle", 1);
        put(v, "dangling prerequisites", danglingCount(t.graph()));
        audit.sort(Comparator.comparingLong(Audit::version));
        Map<String, Flag> replay = new TreeMap<>();
        for (var a : audit) replay = applied(replay, a.change());
        if (!replay.equals(t.graph())) v.put("audit replay does not match the graph", 1);
        var byVersion = new HashMap<Long, Integer>();
        audit.forEach(a -> byVersion.merge(a.version(), 1, Integer::sum));
        int missing = 0;
        for (long i = 1; i <= t.version(); i++) if (!byVersion.containsKey(i)) missing++;
        put(v, "versions with no audit entry", missing);
        put(v, "versions with two audit entries", (int) byVersion.values().stream().filter(c -> c > 1).count());
        var counts = new HashMap<String, Integer>();
        audit.forEach(a -> counts.merge(a.writeId(), 1, Integer::sum));
        put(v, "writes audited twice", (int) counts.values().stream().filter(c -> c > 1).count());
        int lost = 0, falseFail = 0, falseReject = 0;
        for (var rec : ledger) {
            boolean there = counts.containsKey(rec.id());
            if (rec.outcome() == Outcome.ACK && !there) lost++;
            if (rec.outcome() == Outcome.REJECTED && there) falseReject++;
            if ((rec.outcome() == Outcome.UNKNOWN || rec.outcome() == Outcome.GAVE_UP) && there) falseFail++;
        }
        put(v, "acknowledged writes lost", lost);
        put(v, "reported rejected but applied", falseReject);
        put(v, "reported failed but applied", falseFail);
        return v;
    }
    static void put(Map<String, Integer> m, String k, int n) { if (n > 0) m.put(k, n); }

    static final List<String> REPORT = new ArrayList<>();
    static void row(String store, String detail, Map<String, Integer> v) {
        var line = String.format("%-44s %-5s %s%s", store, v.isEmpty() ? "PASS" : "FAIL", detail,
                v.isEmpty() ? "" : (detail.isEmpty() ? "" : " | ") + v);
        System.out.println(line);
        REPORT.add(line);
    }
    static void heading(String h) { System.out.println("\n## " + h); }

    // --- scenarios ---------------------------------------------------------------------------

    static void runAll(int threads, java.util.function.IntConsumer body) throws Exception {
        var ts = new ArrayList<Thread>();
        for (int i = 0; i < threads; i++) {
            int n = i;
            var t = new Thread(() -> { PIN.set(n); body.accept(n); });
            ts.add(t);
            t.start();
        }
        for (var t : ts) t.join();
    }

    /** Section 4, first test: two writers add opposite prerequisites at once. */
    static void race(String name, Store s) throws Exception {
        var w = new Writer(s, Policy.DOC_LOOP, 50, 300_000);
        int rounds = s instanceof TxnNoSnapshot ? 60 : 150, both = 0;
        for (int i = 0; i < rounds; i++) {
            String a = "a" + i, b = "b" + i;
            w.exec("c" + a, new Create(a));
            w.exec("c" + b, new Create(b));
            if (s instanceof LastWriteWins l) { pause(4_000_000); l.sync(); }
            if (s instanceof SplitGuardNoTxn) pause(300_000);
            var outcomes = new Outcome[2];
            var barrier = new CyclicBarrier(2);
            int round = i;
            runAll(2, n -> {
                try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
                outcomes[n] = n == 0 ? w.exec("x" + round, new AddPre(a, b)) : w.exec("y" + round, new AddPre(b, a));
            });
            if (outcomes[0] == Outcome.ACK && outcomes[1] == Outcome.ACK) both++;
        }
        s.finish();
        pause(5_000_000);
        var v = check(s, w.ledger);
        put(v, "rounds where both writers won", both);
        row(name, rounds + " races", v);
    }

    static final String[] KEYS = new String[40];
    static { for (int i = 0; i < 40; i++) KEYS[i] = "f" + i; }

    static Op randomOp() {
        var k = KEYS[r().nextInt(KEYS.length)];
        var o = KEYS[r().nextInt(KEYS.length)];
        int d = r().nextInt(10);
        if (d < 3) return new Switch(k, r().nextBoolean());
        if (d < 6) return new AddPre(k, o);
        if (d < 8) return new RemovePre(k, o);
        return d == 8 ? new Create(k) : new Delete(k);
    }

    /** Mixed workload with readers checking every read they get. */
    static void workload(String name, Store s, Policy p) throws Exception {
        var w = new Writer(s, p, 30, 200_000);
        for (var k : KEYS) w.exec("seed" + k, new Create(k));
        var done = new AtomicBoolean();
        var badReads = new AtomicInteger();
        var reads = new AtomicInteger();
        var reader = new Thread(() -> {
            while (!done.get()) {
                var g = s.read().graph();
                reads.incrementAndGet();
                if (hasCycle(g) || danglingCount(g) > 0) badReads.incrementAndGet();
            }
        });
        reader.start();
        int writers = 8, each = 150;
        runAll(writers, n -> {
            for (int i = 0; i < each; i++) w.exec("w" + n + "-" + i, randomOp());
        });
        done.set(true);
        reader.join();
        s.finish();
        pause(10_000_000);
        var v = check(s, w.ledger);
        put(v, "invalid graphs served to readers", badReads.get());
        var acks = w.ledger.stream().filter(x -> x.outcome() == Outcome.ACK).count();
        var gaveUp = w.ledger.stream().filter(x -> x.outcome() == Outcome.GAVE_UP).count();
        row(name, writers * each + " ops, " + acks + " acked, " + gaveUp + " gave up, " + reads + " reads", v);
    }

    /** Doc: "A read returns one consistent snapshot". Writers keep p and q equal; readers check it. */
    static void torn(String name, Store s) throws Exception {
        var w = new Writer(s, Policy.DOC_LOOP, 200, 100_000);
        w.exec("cp", new Create("p"));
        w.exec("cq", new Create("q"));
        for (int i = 0; i < 25; i++) w.exec("fill" + i, new Create("z" + i));
        var done = new AtomicBoolean();
        var bad = new AtomicInteger();
        var reads = new AtomicInteger();
        var reader = new Thread(() -> {
            while (!done.get()) {
                var g = s.read().graph();
                reads.incrementAndGet();
                if (g.get("p").on() != g.get("q").on()) bad.incrementAndGet();
            }
        });
        reader.start();
        runAll(2, n -> { for (int i = 0; i < 400; i++) w.exec("s" + n + "-" + i, new SetBoth("p", "q", i % 2 == 0)); });
        done.set(true);
        reader.join();
        var v = new LinkedHashMap<String, Integer>();
        put(v, "reads that saw half a two-flag write", bad.get());
        row(name, reads + " reads during two-flag writes", v);
    }

    static void lostAck(String name, java.util.function.Supplier<Store> base) throws Exception {
        for (var p : Policy.values()) workload(name + " + " + p, new LostAck(base.get(), 0.05), p);
    }

    static void failover(String name, boolean sync) throws Exception {
        var s = new FailoverStore(sync);
        var w = new Writer(s, Policy.DOC_LOOP, 30, 200_000);
        for (var k : KEYS) w.exec("seed" + k, new Create(k));
        var t = new Thread(() -> { pause(120_000_000); s.failover(); });
        t.start();
        runAll(4, n -> { for (int i = 0; i < 150; i++) w.exec("w" + n + "-" + i, randomOp()); });
        t.join();
        s.finish();
        var v = check(s, w.ledger);
        put(v, "version numbers used twice", s.reusedVersions);
        row(name, "primary lost mid-run", v);
    }

    static void objectDelete() {
        var s = new CreateOnlyLog();
        var w = new Writer(s, Policy.DOC_LOOP, 5, 0);
        w.exec("c1", new Create("kill-switch"));
        w.exec("on", new Switch("kill-switch", true));
        w.exec("off", new Switch("kill-switch", false));
        s.hideHighest(); // a writer with delete permission hides version 3
        boolean rolledBack = s.read().graph().get("kill-switch").on();
        w.exec("other", new Create("other"));
        var v = check(s, w.ledger);
        v.remove("audit replay does not match the graph");
        if (rolledBack) v.put("kill switch silently back on", 1);
        put(v, "version numbers used twice", s.reusedVersions());
        row("Create-only log, writer may delete", "hide newest object, then write", v);
    }

    static void regressions() throws Exception {
        for (var entry : Map.of("Stale follower reads", new StaleFollowerReads(), "Linearizable reads (control)", new TxnSnapshot()).entrySet()) {
            var s = entry.getValue();
            var w = new Writer(s, Policy.DOC_LOOP, 60, 100_000);
            w.exec("c", new Create("k"));
            var done = new AtomicBoolean();
            var back = new AtomicInteger();
            var reader = new Thread(() -> {
                long last = 0;
                while (!done.get()) {
                    long now = s.read().version();
                    if (now < last) back.incrementAndGet();
                    last = Math.max(last, now);
                    pause(50_000);
                }
            });
            reader.start();
            runAll(4, n -> { for (int i = 0; i < 200; i++) w.exec("w" + n + "-" + i, new Switch("k", i % 2 == 0)); });
            done.set(true);
            reader.join();
            var v = check(s, w.ledger);
            put(v, "reads that went back in time", back.get());
            var gave = w.ledger.stream().filter(x -> x.outcome() == Outcome.GAVE_UP).count();
            row(entry.getKey(), "attempts per op " + String.format("%.1f", w.attempts.get() / (double) w.ledger.size()) + ", gave up " + gave, v);
        }
    }

    static void contention() throws Exception {
        System.out.printf("%-9s %-9s %-9s %-10s %-14s %-10s%n", "writers", "window", "retries", "gave up", "attempts/op", "writes/s");
        for (int writers : new int[] {2, 8, 32}) {
            for (long window : new long[] {1_000_000, 10_000_000}) {
                for (int max : new int[] {3, 10}) {
                    var s = new TxnSnapshot();
                    var w = new Writer(s, Policy.DOC_LOOP, max, window);
                    w.exec("c", new Create("k"));
                    int total = 320, each = total / writers;
                    long t0 = System.nanoTime();
                    runAll(writers, n -> { for (int i = 0; i < each; i++) w.exec("w" + n + "-" + i, new Switch("k", true)); });
                    double secs = (System.nanoTime() - t0) / 1e9;
                    long gave = w.ledger.stream().filter(x -> x.outcome() == Outcome.GAVE_UP).count();
                    long acks = w.ledger.stream().filter(x -> x.outcome() == Outcome.ACK).count();
                    System.out.printf("%-9d %-9s %-9d %-10s %-14.1f %-10.0f%n", writers, window / 1_000_000 + "ms", max,
                            String.format("%.0f%%", 100.0 * gave / w.ledger.size()), w.attempts.get() / (double) w.ledger.size(), acks / secs);
                }
            }
        }
    }

    /** The sandwich read with a limit: how often a slow scan outlasts the gap between writes. */
    static void starvation() throws Exception {
        System.out.printf("%-10s %-14s %-14s %-12s %-10s%n", "scan", "write every", "attempts/read", "max", "failed@20");
        for (long writeMs : new long[] {500, 100, 25, 5}) {
            var meta = new AtomicLong();
            var done = new AtomicBoolean();
            var writer = new Thread(() -> { while (!done.get()) { pause(writeMs * 1_000_000); meta.incrementAndGet(); } });
            writer.start();
            int reads = 30, attempts = 0, max = 0, failed = 0;
            long scanNs = 50_000_000;
            for (int i = 0; i < reads; i++) {
                int a = 0;
                boolean ok = false;
                while (a < 20 && !ok) {
                    a++;
                    long m = meta.get();
                    pause(scanNs);
                    ok = meta.get() == m;
                }
                attempts += a;
                max = Math.max(max, a);
                if (!ok) failed++;
            }
            done.set(true);
            writer.join();
            System.out.printf("%-10s %-14s %-14.1f %-12d %-10s%n", "50ms", writeMs + "ms", attempts / (double) reads, max, failed + "/" + reads);
        }
    }

    /** Arithmetic, not simulation. Assumes the doc's 200-byte flag and a 500-byte audit entry with before and after. */
    static void limits() {
        System.out.println("Audit embedded in the graph document: writes until the document is full");
        System.out.printf("%-10s %-10s %-10s %-10s%n", "cap", "100 flags", "500 flags", "2000 flags");
        for (var cap : new long[][] {{400 * 1024}, {512 * 1024}, {1024 * 1024}, {16 * 1024 * 1024}}) {
            var row = new StringBuilder(String.format("%-10s ", cap[0] / 1024 + " KB"));
            for (long flags : new long[] {100, 500, 2000}) {
                long left = cap[0] - flags * 200;
                row.append(String.format("%-10s ", left <= 0 ? "no room" : String.valueOf(left / 500)));
            }
            System.out.println(row);
        }
        System.out.println("\nOperations in one commit, against transaction limits of 64 and 128 operations");
        System.out.printf("%-12s %-16s %-12s%n", "flags", "whole newGraph", "change only");
        for (int flags : new int[] {50, 100, 500}) System.out.printf("%-12d %-16d %-12d%n", flags, flags + 2, 3);
        System.out.println("\nVersion log: requests to find the newest of N objects, at 1000 keys per list page");
        System.out.printf("%-12s %-18s %-14s%n", "versions", "ascending names", "inverted names");
        for (int n : new int[] {1_000, 10_000, 100_000}) System.out.printf("%-12d %-18d %-14d%n", n, n / 1000 + 1, 1);
        System.out.println("\nVersion log storage if each object holds the whole graph at 200 bytes a flag");
        System.out.printf("%-12s %-12s %-12s%n", "flags", "versions", "total MB");
        for (long[] x : new long[][] {{100, 10_000}, {2000, 10_000}, {2000, 100_000}})
            System.out.printf("%-12d %-12d %-12d%n", x[0], x[1], x[0] * 200 * x[1] / (1024 * 1024));
    }

    // --- real git, real export format, real Evaluator ----------------------------------------

    static String exportFormat(Map<String, List<String>> flags) {
        var out = new StringBuilder("{\n  \"flags\": [");
        var first = true;
        for (var e : flags.entrySet()) {
            out.append(first ? "\n" : ",\n");
            first = false;
            out.append("    {\n      \"key\": \"").append(e.getKey()).append("\",\n      \"switch\": true,\n")
               .append("      \"match\": \"ALL\",\n      \"condition\": null,\n      \"params\": {},\n")
               .append("      \"prerequisites\": [");
            var q = e.getValue().stream().map(p -> "\"" + p + "\"").toList();
            out.append(String.join(", ", q)).append("]\n    }");
        }
        return out.append("\n  ]\n}\n").toString();
    }

    static int git(Path dir, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-c", "user.name=alice", "-c", "user.email=alice@example.test", "-c", "core.autocrlf=false", "-c", "commit.gpgsign=false"));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    static List<FlagConfig> parse(String json) {
        var out = new ArrayList<FlagConfig>();
        var m = Pattern.compile("\"key\": \"(\\w+)\".*?\"prerequisites\": \\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        while (m.find()) {
            var pre = Arrays.stream(m.group(2).split(",")).map(x -> x.replace("\"", "").trim()).filter(x -> !x.isEmpty()).toArray(String[]::new);
            out.add(FlagConfig.of(m.group(1)).on().requires(pre).build());
        }
        return out;
    }

    static void gitScenario(String label, String x, String y) throws Exception {
        var dir = Files.createTempDirectory("flagkit-git-proto");
        git(dir, "init", "-q", "-b", "main");
        var base = new TreeMap<String, List<String>>();
        for (var k : List.of("a", "b", "c", "d", "e")) base.put(k, List.of());
        var file = dir.resolve("flags.json");
        Files.writeString(file, exportFormat(base));
        git(dir, "add", "."); git(dir, "commit", "-qm", "base");

        git(dir, "checkout", "-qb", "pr1");
        var one = new TreeMap<>(base); one.put(x, List.of(y));
        Files.writeString(file, exportFormat(one));
        git(dir, "commit", "-qam", "x requires y");
        var pr1Valid = !hasCycleJson(one);

        git(dir, "checkout", "-q", "main");
        git(dir, "checkout", "-qb", "pr2");
        var two = new TreeMap<>(base); two.put(y, List.of(x));
        Files.writeString(file, exportFormat(two));
        git(dir, "commit", "-qam", "y requires x");
        var pr2Valid = !hasCycleJson(two);

        git(dir, "checkout", "-q", "main");
        git(dir, "merge", "-q", "--no-ff", "-m", "m1", "pr1");
        int code = git(dir, "merge", "-q", "--no-ff", "-m", "m2", "pr2");

        var merged = Files.readString(file);
        var cleanMerge = code == 0 && !merged.contains("<<<<<<<");
        var v = new LinkedHashMap<String, Integer>();
        var detail = "each PR valid alone: " + (pr1Valid && pr2Valid) + ", merged cleanly: " + cleanMerge;
        if (cleanMerge) {
            var graph = parse(merged);
            FlagStore bad = () -> graph;
            try {
                Evaluator.<Object>builder(bad).build().load();
            } catch (InvalidGraphException e) {
                v.put("fresh pod's first load throws InvalidGraphException", 1);
            }
            var live = new AtomicReference<List<FlagConfig>>(parse(exportFormat(base)));
            FlagStore running = live::get;
            var evaluator = Evaluator.<Object>builder(running).build();
            evaluator.load();
            live.set(graph);
            try {
                evaluator.refresh();
            } catch (InvalidGraphException e) {
                detail += ", running pod keeps the old graph";
            }
        }
        row("Git file store, " + label, detail, v);
    }

    static boolean hasCycleJson(Map<String, List<String>> flags) {
        var g = new TreeMap<String, Flag>();
        flags.forEach((k, p) -> g.put(k, new Flag(k, true, p)));
        return hasCycle(g);
    }

    static void gitForgedAuthor() throws Exception {
        var dir = Files.createTempDirectory("flagkit-git-proto");
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("flags.json"), "{}");
        git(dir, "add", ".");
        var p = new ProcessBuilder("git", "-c", "user.name=someone-else", "-c", "user.email=ceo@example.test", "-c", "commit.gpgsign=false", "commit", "-qm", "x")
                .directory(dir.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
        var q = new ProcessBuilder("git", "log", "-1", "--format=%an <%ae>").directory(dir.toFile()).redirectErrorStream(true).start();
        var who = new String(q.getInputStream().readAllBytes()).trim();
        q.waitFor();
        var v = new LinkedHashMap<String, Integer>();
        if (who.contains("ceo@example.test")) v.put("author is whatever the client typed", 1);
        row("Git file store, attribution", "git log shows: " + who, v);
    }

    // --- main --------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        heading("0. Control: the checks must fail on a store with no compare");
        race("No guard (must FAIL)", new NoGuardControl());

        heading("1. Opposite prerequisites race (doc section 4, test 1)");
        race("Multi-key txn + snapshot read", new TxnSnapshot());
        race("Single doc with embedded audit", new SingleDocEmbedded());
        race("Create-only log (put-if-absent)", new CreateOnlyLog());
        race("Atomic commit, no snapshot read", new TxnNoSnapshot());
        race("Stale follower reads, CAS on leader", new StaleFollowerReads());
        race("Single-key CAS, one key per flag", new SplitGuardNoTxn(0));
        race("Last write wins, one key per flag", new LastWriteWins(false));
        race("Last write wins, one document", new LastWriteWins(true));

        heading("2. Mixed workload, 8 writers, readers validating every read");
        workload("Multi-key txn + snapshot read", new TxnSnapshot(), Policy.DOC_LOOP);
        workload("Single doc with embedded audit", new SingleDocEmbedded(), Policy.DOC_LOOP);
        workload("Create-only log (put-if-absent)", new CreateOnlyLog(), Policy.DOC_LOOP);
        workload("Atomic commit, no snapshot read", new TxnNoSnapshot(), Policy.DOC_LOOP);
        workload("Single-key CAS, one key per flag", new SplitGuardNoTxn(0), Policy.DOC_LOOP);

        heading("3. Snapshot reads under a two-flag write (FlagWriter has none today; import would)");
        torn("Multi-key txn + snapshot read", new TxnSnapshot());
        torn("Create-only log (put-if-absent)", new CreateOnlyLog());
        torn("Atomic commit, no snapshot read", new TxnNoSnapshot());

        heading("4. Lost responses, 5% of commits, on every atomic class");
        lostAck("Txn", TxnSnapshot::new);
        lostAck("Log", CreateOnlyLog::new);

        heading("5. Client dies between the steps of a commit (2% of commits)");
        workload("Atomic commit (control)", new CrashAfterCommit(0.02), Policy.DOC_LOOP);
        workload("Single doc, audit in a separate key", new SingleDocSeparateAudit(0.02), Policy.DOC_LOOP);
        workload("Single-key CAS, one key per flag", new SplitGuardNoTxn(0.02), Policy.DOC_LOOP);

        heading("6. Failover");
        failover("Asynchronous replication", false);
        failover("Synchronous or majority (control)", true);

        heading("7. Deleting from the version log");
        objectDelete();

        heading("8. Reads from a lagging follower");
        regressions();

        heading("9. Retry limits on the single guard (no store faults, one flag)");
        contention();

        heading("10. Sandwich read: can a scan outrun the writers");
        starvation();

        heading("11. Limits (arithmetic)");
        limits();

        heading("12. Git file store (real git, real export format, real Evaluator)");
        gitScenario("flags far apart", "a", "e");
        gitScenario("adjacent flags", "a", "b");
        gitForgedAuthor();
    }
}
