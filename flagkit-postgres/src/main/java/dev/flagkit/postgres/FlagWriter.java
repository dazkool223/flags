package dev.flagkit.postgres;

import dev.flagkit.FlagConfig;
import dev.flagkit.Match;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * Changes one environment's flag graph. Every write runs in one READ COMMITTED transaction whose
 * first statement locks the meta row, so all writes to the environment are serialized. That is
 * what makes the cycle check safe: two individually valid changes cannot combine into a cycle.
 * Each write bumps the revision and inserts one audit row in the same transaction.
 */
public final class FlagWriter {

    private final DataSource dataSource;
    private final String flagTable;
    private final String prerequisiteTable;
    private final String paramTable;
    private final String metaTable;
    private final String auditTable;

    public FlagWriter(DataSource dataSource, TablePrefix prefix) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(prefix, "prefix");
        this.flagTable = prefix.table("flag");
        this.prerequisiteTable = prefix.table("prerequisite");
        this.paramTable = prefix.table("param");
        this.metaTable = prefix.table("meta");
        this.auditTable = prefix.table("audit");
    }

    /** Creates a flag with its switch off. */
    public void create(String key, Match match, String conditionName, Map<String, String> params) {
        requireKey(key);
        Objects.requireNonNull(match, "match");
        write("CREATE", key, (c, before) -> {
            if (before != null) {
                throw new FlagWriteException("Flag '" + key + "' already exists");
            }
            update(c, "INSERT INTO " + flagTable + " (flag_key, enabled, match_mode, condition_name)"
                    + " VALUES (?, false, ?, ?)", key, match.name(), conditionName);
            for (var param : params.entrySet()) {
                update(c, "INSERT INTO " + paramTable + " (flag_key, name, value) VALUES (?, ?, ?)",
                        key, param.getKey(), param.getValue());
            }
        });
    }

    /** Deletes a flag. Rejected while other flags have it as a prerequisite. */
    public void delete(String key) {
        write("DELETE", key, (c, before) -> {
            requireExists(key, before);
            var dependants = queryStrings(c, "SELECT flag_key FROM " + prerequisiteTable
                    + " WHERE prerequisite_key = ? ORDER BY flag_key", key);
            if (!dependants.isEmpty()) {
                throw new FlagWriteException("Flag '" + key + "' is a prerequisite of: "
                        + String.join(", ", dependants));
            }
            update(c, "DELETE FROM " + prerequisiteTable + " WHERE flag_key = ?", key);
            update(c, "DELETE FROM " + paramTable + " WHERE flag_key = ?", key);
            update(c, "DELETE FROM " + flagTable + " WHERE flag_key = ?", key);
        });
    }

    public void setSwitch(String key, boolean on) {
        write(on ? "SWITCH_ON" : "SWITCH_OFF", key, (c, before) -> {
            requireExists(key, before);
            update(c, "UPDATE " + flagTable + " SET enabled = ? WHERE flag_key = ?", on, key);
        });
    }

    public void setMatch(String key, Match match) {
        Objects.requireNonNull(match, "match");
        write("MATCH", key, (c, before) -> {
            requireExists(key, before);
            update(c, "UPDATE " + flagTable + " SET match_mode = ? WHERE flag_key = ?", match.name(), key);
        });
    }

    public void setCondition(String key, String conditionName) {
        Objects.requireNonNull(conditionName, "conditionName");
        write("CONDITION_SET", key, (c, before) -> {
            requireExists(key, before);
            update(c, "UPDATE " + flagTable + " SET condition_name = ? WHERE flag_key = ?",
                    conditionName, key);
        });
    }

    /** Removes the condition. Params are left alone; use {@link #unsetParam} to remove them. */
    public void clearCondition(String key) {
        write("CONDITION_CLEAR", key, (c, before) -> {
            requireExists(key, before);
            update(c, "UPDATE " + flagTable + " SET condition_name = NULL WHERE flag_key = ?", key);
        });
    }

    public void setParam(String key, String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        write("PARAM_SET", key, (c, before) -> {
            requireExists(key, before);
            update(c, "INSERT INTO " + paramTable + " (flag_key, name, value) VALUES (?, ?, ?)"
                    + " ON CONFLICT (flag_key, name) DO UPDATE SET value = EXCLUDED.value", key, name, value);
        });
    }

    public void unsetParam(String key, String name) {
        write("PARAM_UNSET", key, (c, before) -> {
            requireExists(key, before);
            if (!before.params().containsKey(name)) {
                throw new FlagWriteException("Flag '" + key + "' has no param '" + name + "'");
            }
            update(c, "DELETE FROM " + paramTable + " WHERE flag_key = ? AND name = ?", key, name);
        });
    }

    /**
     * Makes {@code prerequisite} a prerequisite of {@code key}. Rejected if either flag does not
     * exist or if it would create a cycle; the exception carries the cycle path.
     */
    public void addPrerequisite(String key, String prerequisite) {
        write("PREREQUISITE_ADD", key, (c, before) -> {
            requireExists(key, before);
            if (queryStrings(c, "SELECT flag_key FROM " + flagTable + " WHERE flag_key = ?", prerequisite)
                    .isEmpty()) {
                throw new FlagWriteException("Prerequisite '" + prerequisite + "' does not exist");
            }
            if (before.prerequisites().contains(prerequisite)) {
                throw new FlagWriteException("'" + prerequisite + "' is already a prerequisite of '" + key + "'");
            }
            // Read after the lock was taken, so no concurrent write can change the edges under us.
            var back = findPath(readEdges(c), prerequisite, key);
            if (back != null) {
                var cycle = new ArrayList<String>();
                cycle.add(key);
                cycle.addAll(back);
                throw new CycleDetectedException(cycle);
            }
            update(c, "INSERT INTO " + prerequisiteTable + " (flag_key, prerequisite_key, position)"
                    + " SELECT ?, ?, COALESCE(MAX(position), 0) + 1 FROM " + prerequisiteTable
                    + " WHERE flag_key = ?", key, prerequisite, key);
        });
    }

    public void removePrerequisite(String key, String prerequisite) {
        write("PREREQUISITE_REMOVE", key, (c, before) -> {
            requireExists(key, before);
            if (!before.prerequisites().contains(prerequisite)) {
                throw new FlagWriteException("'" + prerequisite + "' is not a prerequisite of '" + key + "'");
            }
            update(c, "DELETE FROM " + prerequisiteTable + " WHERE flag_key = ? AND prerequisite_key = ?",
                    key, prerequisite);
        });
    }

    private interface Mutation {
        void apply(Connection connection, FlagConfig before) throws SQLException;
    }

    private void write(String operation, String key, Mutation mutation) {
        requireKey(key);
        try (var connection = dataSource.getConnection()) {
            var autoCommit = connection.getAutoCommit();
            var isolation = connection.getTransactionIsolation();
            try {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                // First statement: take the environment-wide write lock.
                queryStrings(connection, "SELECT revision FROM " + metaTable + " WHERE id = 1 FOR UPDATE");
                var before = read(connection, key);
                mutation.apply(connection, before);
                var after = read(connection, key);
                update(connection, "UPDATE " + metaTable + " SET revision = revision + 1 WHERE id = 1");
                insertAudit(connection, operation, key, before, after);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setTransactionIsolation(isolation);
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new FlagStoreException("Could not write to the flag store: " + e.getMessage(), e);
        }
    }

    private void insertAudit(Connection c, String operation, String key, FlagConfig before, FlagConfig after)
            throws SQLException {
        // actor and occurred_at are left to the database defaults, so the caller cannot set them.
        try (var statement = c.prepareStatement("INSERT INTO " + auditTable
                + " (operation, flag_key, before_value, after_value) VALUES (?, ?, ?::jsonb, ?::jsonb)")) {
            statement.setString(1, operation);
            statement.setString(2, key);
            setNullableString(statement, 3, before == null ? null : toJson(before));
            setNullableString(statement, 4, after == null ? null : toJson(after));
            statement.executeUpdate();
        }
    }

    private static void setNullableString(java.sql.PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /** Reads one flag in full, or null if it does not exist. */
    private FlagConfig read(Connection c, String key) throws SQLException {
        try (var statement = c.prepareStatement(
                "SELECT enabled, match_mode, condition_name FROM " + flagTable + " WHERE flag_key = ?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                var on = rows.getBoolean(1);
                var match = Match.valueOf(rows.getString(2));
                var condition = rows.getString(3);
                var prerequisites = queryStrings(c, "SELECT prerequisite_key FROM " + prerequisiteTable
                        + " WHERE flag_key = ? ORDER BY position", key);
                var params = new LinkedHashMap<String, String>();
                try (var paramStatement = c.prepareStatement(
                        "SELECT name, value FROM " + paramTable + " WHERE flag_key = ? ORDER BY name")) {
                    paramStatement.setString(1, key);
                    try (var paramRows = paramStatement.executeQuery()) {
                        while (paramRows.next()) {
                            params.put(paramRows.getString(1), paramRows.getString(2));
                        }
                    }
                }
                return new FlagConfig(key, on, match, condition, params, prerequisites);
            }
        }
    }

    private Map<String, List<String>> readEdges(Connection c) throws SQLException {
        var edges = new HashMap<String, List<String>>();
        try (var statement = c.createStatement();
             var rows = statement.executeQuery(
                     "SELECT flag_key, prerequisite_key FROM " + prerequisiteTable)) {
            while (rows.next()) {
                edges.computeIfAbsent(rows.getString(1), k -> new ArrayList<>()).add(rows.getString(2));
            }
        }
        return edges;
    }

    /**
     * Depth-first search along prerequisite edges from {@code from} looking for {@code target}.
     * Returns the path including both ends, or null. Iterative, so graph depth is unbounded; the
     * visited set keeps a shared prerequisite from being walked twice.
     */
    static List<String> findPath(Map<String, List<String>> edges, String from, String target) {
        var parent = new HashMap<String, String>();
        var visited = new HashSet<String>();
        var stack = new ArrayDeque<String>();
        stack.push(from);
        visited.add(from);
        while (!stack.isEmpty()) {
            var current = stack.pop();
            if (current.equals(target)) {
                var path = new ArrayList<String>();
                for (var node = current; node != null; node = parent.get(node)) {
                    path.add(node);
                }
                Collections.reverse(path);
                return path;
            }
            for (var next : edges.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    parent.put(next, current);
                    stack.push(next);
                }
            }
        }
        return null;
    }

    private static void requireKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Flag key must not be empty");
        }
    }

    private static void requireExists(String key, FlagConfig before) {
        if (before == null) {
            throw new FlagWriteException("Flag '" + key + "' does not exist");
        }
    }

    private static List<String> queryStrings(Connection c, String sql, String... args) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            for (var i = 0; i < args.length; i++) {
                statement.setString(i + 1, args[i]);
            }
            try (var rows = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
                return values;
            }
        }
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (var statement = c.prepareStatement(sql)) {
            for (var i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    /** Hand-written JSON with sorted params, so audit values are stable and the module needs no library. */
    static String toJson(FlagConfig flag) {
        var json = new StringBuilder("{\"switch\":").append(flag.on())
                .append(",\"match\":").append(quote(flag.match().name()))
                .append(",\"condition\":").append(flag.conditionName() == null ? "null" : quote(flag.conditionName()))
                .append(",\"params\":{");
        var first = true;
        for (var param : new TreeMap<>(flag.params()).entrySet()) {
            json.append(first ? "" : ",").append(quote(param.getKey())).append(':').append(quote(param.getValue()));
            first = false;
        }
        json.append("},\"prerequisites\":[");
        first = true;
        for (var prerequisite : flag.prerequisites()) {
            json.append(first ? "" : ",").append(quote(prerequisite));
            first = false;
        }
        return json.append("]}").toString();
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
