package dev.flagkit.cli;

/** The CLI config file or the settings in it cannot be used. */
final class ConfigException extends RuntimeException {

    ConfigException(String message) {
        super(message);
    }
}
