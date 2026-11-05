package dev.flagkit.postgres;

/** The database could not be read or written. The message never includes connection details. */
public class FlagStoreException extends RuntimeException {

    public FlagStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
