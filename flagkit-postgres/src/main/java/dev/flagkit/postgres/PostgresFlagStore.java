package dev.flagkit.postgres;

import dev.flagkit.FlagConfig;
import dev.flagkit.FlagStore;
import dev.flagkit.Match;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Reads one environment's whole flag graph from Postgres. All tables are read in a single
 * read-only REPEATABLE READ transaction, so a load never sees half of a write.
 */
public final class PostgresFlagStore implements FlagStore {

    private final DataSource dataSource;
    private final String flagTable;
    private final String prerequisiteTable;
    private final String paramTable;

    public PostgresFlagStore(DataSource dataSource, TablePrefix prefix) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(prefix, "prefix");
        this.flagTable = prefix.table("flag");
        this.prerequisiteTable = prefix.table("prerequisite");
        this.paramTable = prefix.table("param");
    }

    @Override
    public List<FlagConfig> load() {
        try (var connection = dataSource.getConnection()) {
            var autoCommit = connection.getAutoCommit();
            var isolation = connection.getTransactionIsolation();
            var readOnly = connection.isReadOnly();
            try {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                var flags = read(connection);
                connection.commit();
                return flags;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setReadOnly(readOnly);
                connection.setTransactionIsolation(isolation);
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new FlagStoreException("Could not load the flag graph: " + e.getMessage(), e);
        }
    }

    private List<FlagConfig> read(Connection connection) throws SQLException {
        var prerequisites = new HashMap<String, List<String>>();
        var params = new HashMap<String, Map<String, String>>();
        try (var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT flag_key, prerequisite_key FROM " + prerequisiteTable
                            + " ORDER BY flag_key, position")) {
                while (rows.next()) {
                    prerequisites.computeIfAbsent(rows.getString(1), k -> new ArrayList<>())
                            .add(rows.getString(2));
                }
            }
            try (var rows = statement.executeQuery(
                    "SELECT flag_key, name, value FROM " + paramTable)) {
                while (rows.next()) {
                    params.computeIfAbsent(rows.getString(1), k -> new LinkedHashMap<>())
                            .put(rows.getString(2), rows.getString(3));
                }
            }
            var flags = new ArrayList<FlagConfig>();
            try (var rows = statement.executeQuery(
                    "SELECT flag_key, enabled, match_mode, condition_name FROM " + flagTable
                            + " ORDER BY flag_key")) {
                while (rows.next()) {
                    var key = rows.getString(1);
                    flags.add(new FlagConfig(key, rows.getBoolean(2), Match.valueOf(rows.getString(3)),
                            rows.getString(4), params.getOrDefault(key, Map.of()),
                            prerequisites.getOrDefault(key, List.of())));
                }
            }
            return flags;
        }
    }
}
