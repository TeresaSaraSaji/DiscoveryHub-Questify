package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;

/**
 * A new case. The only legal move is to open the investigation ({@code ACTIVE}). A draft is not
 * read-only — the investigator is still shaping it.
 */
final class DraftState implements CaseState {

    static final DraftState INSTANCE = new DraftState();

    private DraftState() {
    }

    @Override
    public CaseStatus status() {
        return CaseStatus.DRAFT;
    }

    @Override
    public boolean canTransitionTo(CaseStatus target) {
        return target == CaseStatus.ACTIVE;
    }

    @Override
    public CaseState transitionTo(CaseStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalCaseTransitionException(CaseStatus.DRAFT, target);
        }
        return CaseStateFactory.forStatus(target);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}
