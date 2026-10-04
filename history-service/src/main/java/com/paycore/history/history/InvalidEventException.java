package com.paycore.history.history;

/** A record that can never be stored as it is. Not retryable: it goes to the DLT (spec §6.4). */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
