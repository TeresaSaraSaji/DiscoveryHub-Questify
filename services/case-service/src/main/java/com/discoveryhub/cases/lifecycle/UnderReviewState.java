package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;

/**
 * A case under review. The legal move is to close it ({@code CLOSED}). Review is not read-only
 * either — an investigator can still add evidence discovered during review — but closing ends that.
 */
final class UnderReviewState implements CaseState {

    static final UnderReviewState INSTANCE = new UnderReviewState();

    private UnderReviewState() {
    }

    @Override
    public CaseStatus status() {
        return CaseStatus.UNDER_REVIEW;
    }

    @Override
    public boolean canTransitionTo(CaseStatus target) {
        return target == CaseStatus.CLOSED;
    }

    @Override
    public CaseState transitionTo(CaseStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalCaseTransitionException(CaseStatus.UNDER_REVIEW, target);
        }
        return CaseStateFactory.forStatus(target);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }
}
