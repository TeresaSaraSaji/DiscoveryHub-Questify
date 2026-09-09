package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;

/**
 * Raised when a transition is requested that is not legal from the case's current state (FR-2.2:
 * "invalid transitions must be rejected with a clear error"). Mapped to HTTP 409 Conflict by the
 * API exception handler so the caller sees the attempted and disallowed statuses.
 */
public class IllegalCaseTransitionException extends RuntimeException {

    private final CaseStatus from;
    private final CaseStatus to;

    public IllegalCaseTransitionException(CaseStatus from, CaseStatus to) {
        super("illegal case transition: " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public CaseStatus from() {
        return from;
    }

    public CaseStatus to() {
        return to;
    }
}
