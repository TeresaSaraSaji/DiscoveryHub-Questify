package com.discoveryhub.holds.scope;

/**
 * Raised when a hold scope resolves to zero messages. An empty coverage table would make
 * {@code GET /holds/check} answer "not held" for everything in the case — the unsafe direction
 * for a protective hold — so the resolver refuses to return an empty set and the worker marks the
 * hold FAILED rather than persisting empty coverage. The user corrects the scope and retries.
 */
public class EmptyScopeException extends RuntimeException {

    private final String holdId;

    public EmptyScopeException(String holdId) {
        super("hold scope resolved to zero messages: " + holdId);
        this.holdId = holdId;
    }

    public String holdId() {
        return holdId;
    }
}
