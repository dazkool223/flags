package dev.flagkit.cli;

import dev.flagkit.FlagConfig;
import dev.flagkit.Match;
import dev.flagkit.postgres.FlagStoreException;
import dev.flagkit.postgres.FlagWriteException;
import dev.flagkit.postgres.FlagWriter;
import dev.flagkit.postgres.PostgresFlagStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Every command. Exit codes: 0 success, 1 the change was rejected or the input was wrong, 2 bad
 * usage (picocli), 3 the database could not be reached or failed.
 */
final class Commands {

    static final int REJECTED = 1;
    static final int DATABASE = 3;

    private Commands() {}

    /** The options every command takes: which environment, and optionally which config file. */
    static final class Target {
        @Option(names = {"-e", "--env"}, required = true, paramLabel = "<env>",
                description = "Environment from the config file, for example dev or prod.")
        String environment;

        @Option(names = "--config", paramLabel = "<file>",
                description = "Config file. Default: $FLAGKIT_CONFIG, then ~/.flagkit/config.properties.")
        Path config;

        ConnectionSettings settings() {
            return ConnectionSettings.load(resolveConfig(), environment);
        }

        private Path resolveConfig() {
            if (config != null) {
                return config;
            }
            var fromEnvironment = System.getenv("FLAGKIT_CONFIG");
            if (fromEnvironment != null && !fromEnvironment.isBlank()) {
                return Path.of(fromEnvironment);
            }
            return Path.of(System.getProperty("user.home"), ".flagkit", "config.properties");
        }
    }

    /** What a command gets once the connection settings are known. */
    static final class Session {
        final ConnectionSettings settings;
        final PrintWriter out;
        private final javax.sql.DataSource dataSource;

        Session(ConnectionSettings settings, PrintWriter out) {
            this.settings = settings;
            this.out = out;
            this.dataSource = settings.dataSource();
        }

        /** Prints a line ending in "\n" on every platform, so output is the same everywhere. */
        void line(String text) {
            out.print(text + "\n");
        }

        FlagWriter writer() {
            return new FlagWriter(dataSource, settings.prefix());
        }

        PostgresFlagStore store() {
            return new PostgresFlagStore(dataSource, settings.prefix());
        }
    }

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:\\S+");
    private static final Pattern PASSWORD = Pattern.compile("(?i)(password|passwd|pwd)\\s*[=:]\\s*\\S+");

    abstract static class Base implements Callable<Integer> {
        @Mixin Target target;
        @Spec CommandSpec spec;

        abstract void run(Session session) throws IOException;

        @Override
        public final Integer call() {
            var out = spec.commandLine().getOut();
            var err = spec.commandLine().getErr();
            ConnectionSettings settings;
            try {
                settings = target.settings();
            } catch (ConfigException e) {
                err.println("error: " + e.getMessage());
                return REJECTED;
            }
            try {
                run(new Session(settings, out));
                return 0;
            } catch (FlagWriteException e) {
                err.println("rejected: " + e.getMessage());
                return REJECTED;
            } catch (FlagStoreException e) {
                err.println("database error (" + settings.describe() + "): " + sanitize(e.getMessage()));
                return DATABASE;
            } catch (IOException e) {
                err.println("error: " + sanitize(e.getMessage()));
                return REJECTED;
            } catch (IllegalArgumentException e) {
                err.println("error: " + e.getMessage());
                return REJECTED;
            } catch (RuntimeException e) {
                err.println("internal error: " + e.getClass().getSimpleName() + ": " + sanitize(e.getMessage()));
                return REJECTED;
            } finally {
                out.flush();
                err.flush();
            }
        }
    }

    /** Drops anything that looks like a connection string or a password from a driver message. */
    static String sanitize(String message) {
        if (message == null) {
            return "no further details";
        }
        var firstLine = message.lines().findFirst().orElse(message);
        return PASSWORD.matcher(JDBC_URL.matcher(firstLine).replaceAll("<connection>"))
                .replaceAll("$1=<hidden>");
    }

    // --- write commands ----------------------------------------------------------------------

    @Command(name = "create", mixinStandardHelpOptions = true,
            description = "Create a flag. Its switch starts off.")
    static final class Create extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Option(names = "--match", defaultValue = "ALL", description = "ALL (default) or ANY.") Match match;
        @Option(names = "--condition", paramLabel = "<name>", description = "Registered condition name.")
        String condition;
        @Option(names = {"-p", "--param"}, paramLabel = "<name=value>", description = "Condition param. Repeatable.")
        Map<String, String> params = new LinkedHashMap<>();

        @Override
        void run(Session s) {
            s.writer().create(key, match, condition, params);
            s.line("created " + key);
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true,
            description = "Delete a flag. Rejected while other flags have it as a prerequisite.")
    static final class Delete extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;

        @Override
        void run(Session s) {
            s.writer().delete(key);
            s.line("deleted " + key);
        }
    }

    enum State { on, off }

    @Command(name = "switch", mixinStandardHelpOptions = true, description = "Turn a flag's switch on or off.")
    static final class Switch extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Parameters(index = "1", paramLabel = "on|off") State state;

        @Override
        void run(Session s) {
            s.writer().setSwitch(key, state == State.on);
            s.line(key + " switched " + state);
        }
    }

    @Command(name = "match", mixinStandardHelpOptions = true, description = "Set how a flag combines its prerequisites.")
    static final class MatchCmd extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Parameters(index = "1", paramLabel = "ALL|ANY") Match match;

        @Override
        void run(Session s) {
            s.writer().setMatch(key, match);
            s.line(key + " match " + match);
        }
    }

    @Command(name = "condition", mixinStandardHelpOptions = true, description = "Set or clear a flag's condition.",
            subcommands = {ConditionSet.class, ConditionClear.class})
    static final class Condition implements Runnable {
        @Spec CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getErr());
        }
    }

    @Command(name = "set", mixinStandardHelpOptions = true, description = "Attach a registered condition to a flag.")
    static final class ConditionSet extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Parameters(index = "1", paramLabel = "<condition>") String condition;

        @Override
        void run(Session s) {
            s.writer().setCondition(key, condition);
            s.line(key + " condition " + condition);
        }
    }

    @Command(name = "clear", mixinStandardHelpOptions = true,
            description = "Remove a flag's condition. Params stay until unset.")
    static final class ConditionClear extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;

        @Override
        void run(Session s) {
            s.writer().clearCondition(key);
            s.line(key + " condition cleared");
        }
    }

    @Command(name = "param", mixinStandardHelpOptions = true, description = "Set or remove a condition param.",
            subcommands = {ParamSet.class, ParamUnset.class})
    static final class Param implements Runnable {
        @Spec CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getErr());
        }
    }

    @Command(name = "set", mixinStandardHelpOptions = true, description = "Set a param, replacing any old value.")
    static final class ParamSet extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Parameters(index = "1", paramLabel = "<name>") String name;
        @Parameters(index = "2", paramLabel = "<value>") String value;

        @Override
        void run(Session s) {
            s.writer().setParam(key, name, value);
            s.line(key + " param " + name + "=" + value);
        }
    }

    @Command(name = "unset", mixinStandardHelpOptions = true, description = "Remove a param.")
    static final class ParamUnset extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Parameters(index = "1", paramLabel = "<name>") String name;

        @Override
        void run(Session s) {
            s.writer().unsetParam(key, name);
            s.line(key + " param " + name + " removed");
        }
    }

    @Command(name = "prerequisite", mixinStandardHelpOptions = true, description = "Add or remove a prerequisite.",
            subcommands = {PrerequisiteAdd.class, PrerequisiteRemove.class})
    static final class Prerequisite implements Runnable {
        @Spec CommandSpec spec;

        @Override
        public void run() {
            spec.commandLine().usage(spec.commandLine().getErr());
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true,
            description = "Make <prerequisite> a prerequisite of <key>. Rejected if it would create a cycle.")
    static final class PrerequisiteAdd extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Parameters(index = "1", paramLabel = "<prerequisite>") String prerequisite;

        @Override
        void run(Session s) {
            s.writer().addPrerequisite(key, prerequisite);
            s.line(key + " now requires " + prerequisite);
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true, description = "Remove a prerequisite.")
    static final class PrerequisiteRemove extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;
        @Parameters(index = "1", paramLabel = "<prerequisite>") String prerequisite;

        @Override
        void run(Session s) {
            s.writer().removePrerequisite(key, prerequisite);
            s.line(key + " no longer requires " + prerequisite);
        }
    }

    // --- read commands -----------------------------------------------------------------------

    @Command(name = "graph", mixinStandardHelpOptions = true, description = "Print the environment's graph as a tree.")
    static final class Graph extends Base {
        @Override
        void run(Session s) {
            s.out.print(Render.tree(s.store().load()));
        }
    }

    @Command(name = "show", mixinStandardHelpOptions = true, description = "Show one flag in full.")
    static final class Show extends Base {
        @Parameters(index = "0", paramLabel = "<key>") String key;

        @Override
        void run(Session s) {
            FlagConfig flag = s.store().load().stream().filter(f -> f.key().equals(key)).findFirst()
                    .orElseThrow(() -> new FlagWriteException("Flag '" + key + "' does not exist"));
            s.out.print(Render.show(flag));
        }
    }

    @Command(name = "export", mixinStandardHelpOptions = true,
            description = "Write the whole graph as sorted, diff-friendly JSON.")
    static final class Export extends Base {
        @Option(names = {"-o", "--output"}, paramLabel = "<file>", description = "File to write. Default: standard output.")
        Path output;

        @Override
        void run(Session s) throws IOException {
            var text = Render.export(s.store().load());
            if (output == null) {
                s.out.print(text);
            } else {
                Files.writeString(output, text, StandardCharsets.UTF_8);
                s.line("exported to " + output);
            }
        }
    }
}
