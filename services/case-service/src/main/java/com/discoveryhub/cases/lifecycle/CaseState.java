package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;

/**
 * State pattern (behavioural) for the case lifecycle (FR-2.2). Each concrete state encapsulates
 * the transitions that are legal <i>from</i> it and whether the case is still mutable. The service
 * never holds a {@code switch} on status to decide legality — it asks the state object, so a new
 * state is an additive change (OCP) and the transition table lives with the state it governs.
 *
 * <p>States are singletons: the {@link CaseStateFactory} hands out the one instance per status, so
 * {@link #transitionTo(CaseStatus)} can return the next state by asking the factory rather than
 * holding references to every other state (which would couple every state to every other).
 *
 * <p>Liskov substitution: every implementation answers the same questions the same way. A caller
 * holding a {@code CaseState} does not know or care which concrete state it has.
 */
public interface CaseState {

    /** The status this state represents. */
    CaseStatus status();

    /** Whether the target status is a legal transition from this state. */
    boolean canTransitionTo(CaseStatus target);

    /**
     * Transition to the target status, returning the new state, or throw
     * {@link IllegalCaseTransitionException} if the transition is not legal from this state.
     */
    CaseState transitionTo(CaseStatus target);

    /**
     * Whether the case is read-only in this state. A closed case accepts no new custodians,
     * evidence, holds, or exports (FR-2.4).
     */
    boolean isReadOnly();
}
