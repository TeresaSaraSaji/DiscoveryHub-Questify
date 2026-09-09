package com.discoveryhub.search.exception;

/**
 * A search-service failure the caller can be told about with a specific status — a request with no
 * criteria, an oversized page, an indexing failure. Carries an HTTP status so
 * {@link GlobalExceptionHandler} can map it without a per-case switch.
 *
 * <p>The default status is 500: an indexing or lookup failure is a server problem, not a client
 * one, and a caller should not be told to fix its request. Use the status-bearing constructors for
 * client-fault failures where the caller can act on the message.
 */
public class SearchException extends RuntimeException {

    private final int status;

    public SearchException(String message) {
        this(message, 500);
    }

    public SearchException(String message, int status) {
        super(message);
        this.status = status;
    }

    public SearchException(String message, Throwable cause) {
        super(message, cause);
        this.status = 500;
    }

    public int status() {
        return status;
    }
}
