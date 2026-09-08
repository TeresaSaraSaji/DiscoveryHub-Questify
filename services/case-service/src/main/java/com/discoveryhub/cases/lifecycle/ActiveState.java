package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;

/**
 * An open investigation: custodians and evidence can be added, holds can be placed. The only
 * legal move is to send the case into review ({@code UNDER_REVIEW}). Closing from ACTIVE is not
 * allowed — the documented lifecycle is {@code DRAFT -> ACTIVE -> UNDER_REVIEW -> CLOSED}, so the
 * case must pass through review before it can be closed (FR-2.2).
 */
final class ActiveState implements CaseState {

    static final ActiveState INSTANCE = new ActiveState();

    private ActiveState() {
    }

    @Override
    public CaseStatus status() {
        return CaseStatus.ACTIVE;
    }

    @Override
    public boolean canTransitionTo(CaseStatus target) {
        return target == CaseStatus.UNDER_REVIEW;
    }

    @Override
    public CaseState transitionTo(CaseStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalCaseTransitionException(CaseStatus.ACTIVE, target);
        }
        return CaseStateFactory.forStatus(target);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}
