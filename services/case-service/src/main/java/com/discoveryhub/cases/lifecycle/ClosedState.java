package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;

/**
 * A closed case. Terminal: no transitions out (FR-2.2), and read-only: no new custodians,
 * evidence, holds, or exports (FR-2.4). The service enforces read-only-ness by asking
 * {@link #isReadOnly()} before any mutation, so a closed case rejects writes through the same gate
 * the lifecycle uses for transitions.
 */
final class ClosedState implements CaseState {

    static final ClosedState INSTANCE = new ClosedState();

    private ClosedState() {
    }

    @Override
    public CaseStatus status() {
        return CaseStatus.CLOSED;
    }

    @Override
    public boolean canTransitionTo(CaseStatus target) {
        return false;
    }

    @Override
    public CaseState transitionTo(CaseStatus target) {
        throw new IllegalCaseTransitionException(CaseStatus.CLOSED, target);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }
}
