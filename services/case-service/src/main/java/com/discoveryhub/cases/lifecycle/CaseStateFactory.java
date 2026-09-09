package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;

import java.util.Map;

/**
 * Factory method (creational) for {@link CaseState}. Maps a persisted {@link CaseStatus} to the
 * singleton state object that governs its transitions. The service rehydrates a case's state from
 * the {@code status} column via this factory, so the persistence layer and the state machine are
 * decoupled: the entity stores an enum, the behaviour lives in the state, and this is the join.
 *
 * <p>Centralising the mapping here means a new state is added in two places — the enum, and an
 * entry in {@code STATES} — and every existing state's {@code transitionTo} that targets it just
 * works, because they resolve the next state through {@link #forStatus(CaseStatus)} too.
 */
public final class CaseStateFactory {

    private static final Map<CaseStatus, CaseState> STATES = Map.of(
            CaseStatus.DRAFT, DraftState.INSTANCE,
            CaseStatus.ACTIVE, ActiveState.INSTANCE,
            CaseStatus.UNDER_REVIEW, UnderReviewState.INSTANCE,
            CaseStatus.CLOSED, ClosedState.INSTANCE);

    private CaseStateFactory() {
    }

    /** The state object governing the given status. Throws if no state is registered. */
    public static CaseState forStatus(CaseStatus status) {
        CaseState state = STATES.get(status);
        if (state == null) {
            throw new IllegalStateException("no CaseState registered for status " + status);
        }
        return state;
    }
}
