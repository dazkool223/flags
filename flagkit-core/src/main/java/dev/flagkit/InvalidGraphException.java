package dev.flagkit;

/** The stored graph cannot be used: an unknown condition, a dangling prerequisite or a cycle. */
public class InvalidGraphException extends RuntimeException {

    public InvalidGraphException(String message) {
        super(message);
    }
}
