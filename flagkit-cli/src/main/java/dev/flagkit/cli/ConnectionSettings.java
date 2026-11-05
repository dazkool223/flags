package dev.flagkit.cli;

import dev.flagkit.postgres.TablePrefix;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The non-secret connection settings of one environment. There is deliberately no password
 * field. The JDBC driver finds the password in the user's {@code .pgpass} file, or in the file
 * named by the PGPASSFILE environment variable.
 */
record ConnectionSettings(String environment, String host, int port, String database, String user,
                          TablePrefix prefix, String sslMode) {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final Set<String> SSL_MODES =
            Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    /**
     * Reads one environment from a properties file with keys like {@code prod.host}. Recognised
     * keys are host, port, database, user, prefix and sslmode.
     */
    static ConnectionSettings load(Path config, String environment) {
        var properties = new Properties();
        try (var in = Files.newBufferedReader(config)) {
            properties.load(in);
        } catch (NoSuchFileException e) {
            throw new ConfigException("Config file not found: " + config
                    + " (use --config or set FLAGKIT_CONFIG)");
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file " + config + ": " + e.getClass().getSimpleName());
        }
        var environments = new TreeSet<String>();
        for (var name : properties.stringPropertyNames()) {
            var dot = name.indexOf('.');
            if (dot > 0) {
                environments.add(name.substring(0, dot));
            }
            if (name.endsWith(".password") || name.endsWith(".pass") || name.equals("password")) {
                throw new ConfigException("The config file must not hold passwords (found key '" + name
                        + "'). Put the password in .pgpass.");
            }
        }
        if (!environments.contains(environment)) {
            throw new ConfigException("Unknown environment '" + environment + "'. Known environments: "
                    + (environments.isEmpty() ? "none" : String.join(", ", environments)));
        }
        return fromProperties(environment, properties);
    }

    private static ConnectionSettings fromProperties(String environment, Properties properties) {
        var get = (java.util.function.Function<String, String>) key -> {
            var value = properties.getProperty(environment + "." + key);
            return value == null || value.isBlank() ? null : value.strip();
        };
        var host = required(environment, "host", get.apply("host"));
        var database = required(environment, "database", get.apply("database"));
        var user = required(environment, "user", get.apply("user"));
        var port = 5432;
        if (get.apply("port") != null) {
            try {
                port = Integer.parseInt(get.apply("port"));
            } catch (NumberFormatException e) {
                throw new ConfigException("Environment '" + environment + "': port must be a number");
            }
        }
        TablePrefix prefix;
        try {
            prefix = TablePrefix.of(get.apply("prefix") == null ? "" : get.apply("prefix"));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Environment '" + environment + "': " + e.getMessage());
        }
        return new ConnectionSettings(environment, host, port, database, user, prefix,
                resolveSslMode(environment, host, get.apply("sslmode")));
    }

    private static String required(String environment, String key, String value) {
        if (value == null) {
            throw new ConfigException("Environment '" + environment + "' is missing '" + key + "'");
        }
        return value;
    }

    /**
     * Local hosts default to {@code prefer}. Every other host defaults to {@code verify-full}
     * (encrypted, certificate chain and host name checked), and nothing weaker is accepted.
     */
    static String resolveSslMode(String environment, String host, String configured) {
        var local = LOCAL_HOSTS.contains(host.toLowerCase());
        if (configured == null) {
            return local ? "prefer" : "verify-full";
        }
        if (!SSL_MODES.contains(configured)) {
            throw new ConfigException("Environment '" + environment + "': unknown sslmode '" + configured + "'");
        }
        if (!local && !configured.equals("verify-full")) {
            throw new ConfigException("Environment '" + environment + "': host '" + host
                    + "' is not local, so sslmode must be verify-full (or left unset)");
        }
        return configured;
    }

    /** Host, database and user, safe to print. Never the connection string. */
    String describe() {
        return "host=" + host + " database=" + database + " user=" + user;
    }

    DataSource dataSource() {
        var source = new PGSimpleDataSource();
        source.setServerNames(new String[] {host});
        source.setPortNumbers(new int[] {port});
        source.setDatabaseName(database);
        source.setUser(user);
        source.setSslMode(sslMode);
        source.setApplicationName("flagkit-cli");
        source.setLoginTimeout(10);
        return source;
    }
}
