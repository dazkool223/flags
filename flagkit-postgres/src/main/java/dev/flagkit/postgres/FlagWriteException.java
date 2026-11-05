package dev.flagkit.postgres;

/** A write was rejected because it would break the flag graph or refers to something missing. */
public class FlagWriteException extends RuntimeException {

    public FlagWriteException(String message) {
        super(message);
    }
}
