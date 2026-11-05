package dev.flagkit.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Changes and inspects the flag graph of one environment. The password is never an option or an
 * environment variable. The JDBC driver reads it from the user's .pgpass file.
 */
@Command(name = "flagkit", mixinStandardHelpOptions = true, version = "flagkit 0.1.0-SNAPSHOT",
        description = "Manage flagkit flags in Postgres.",
        footer = {"", "Exit codes: 0 ok, 1 rejected or bad input, 2 bad usage, 3 database error."},
        subcommands = {
                Commands.Create.class, Commands.Delete.class, Commands.Switch.class, Commands.MatchCmd.class,
                Commands.Condition.class, Commands.Param.class, Commands.Prerequisite.class,
                Commands.Graph.class, Commands.Show.class, Commands.Export.class})
public final class FlagkitCli implements Runnable {

    @Spec CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getErr());
    }

    /** The configured command line, also used by tests to run commands in-process. */
    static CommandLine commandLine() {
        return new CommandLine(new FlagkitCli()).setCaseInsensitiveEnumValuesAllowed(true);
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }
}
