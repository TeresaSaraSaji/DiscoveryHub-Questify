package com.discoveryhub.cases.lifecycle;

import com.discoveryhub.cases.domain.CaseStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the case lifecycle (FR-2.2): the documented forward path is legal, every other move is
 * rejected, and CLOSED is terminal and read-only. Tests run against the State objects directly, so
 * the transition rules are covered without the repository or Kafka on the critical path.
 */
class CaseStateMachineTest {

    @Test
    void draftTransitionsToActive() {
        CaseState state = CaseStateFactory.forStatus(CaseStatus.DRAFT);

        assertThat(state.canTransitionTo(CaseStatus.ACTIVE)).isTrue();
        assertThat(state.transitionTo(CaseStatus.ACTIVE).status()).isEqualTo(CaseStatus.ACTIVE);
    }

    @Test
    void activeTransitionsToUnderReview() {
        CaseState state = CaseStateFactory.forStatus(CaseStatus.ACTIVE);

        assertThat(state.canTransitionTo(CaseStatus.UNDER_REVIEW)).isTrue();
        assertThat(state.transitionTo(CaseStatus.UNDER_REVIEW).status()).isEqualTo(CaseStatus.UNDER_REVIEW);
    }

    @Test
    void underReviewTransitionsToClosed() {
        CaseState state = CaseStateFactory.forStatus(CaseStatus.UNDER_REVIEW);

        assertThat(state.canTransitionTo(CaseStatus.CLOSED)).isTrue();
        assertThat(state.transitionTo(CaseStatus.CLOSED).status()).isEqualTo(CaseStatus.CLOSED);
    }

    @Test
    void closingDirectlyFromActiveIsRejected() {
        CaseState state = CaseStateFactory.forStatus(CaseStatus.ACTIVE);

        assertThat(state.canTransitionTo(CaseStatus.CLOSED)).isFalse();
        assertThatThrownBy(() -> state.transitionTo(CaseStatus.CLOSED))
                .isInstanceOf(IllegalCaseTransitionException.class);
    }

    @Test
    void draftCannotJumpToUnderReviewOrClosed() {
        CaseState state = CaseStateFactory.forStatus(CaseStatus.DRAFT);

        assertThat(state.canTransitionTo(CaseStatus.UNDER_REVIEW)).isFalse();
        assertThat(state.canTransitionTo(CaseStatus.CLOSED)).isFalse();
    }

    @Test
    void closedIsTerminal() {
        CaseState state = CaseStateFactory.forStatus(CaseStatus.CLOSED);

        for (CaseStatus target : CaseStatus.values()) {
            assertThat(state.canTransitionTo(target)).isFalse();
        }
        assertThatThrownBy(() -> state.transitionTo(CaseStatus.ACTIVE))
                .isInstanceOf(IllegalCaseTransitionException.class);
    }

    @Test
    void onlyClosedIsReadOnly() {
        assertThat(CaseStateFactory.forStatus(CaseStatus.DRAFT).isReadOnly()).isFalse();
        assertThat(CaseStateFactory.forStatus(CaseStatus.ACTIVE).isReadOnly()).isFalse();
        assertThat(CaseStateFactory.forStatus(CaseStatus.UNDER_REVIEW).isReadOnly()).isFalse();
        assertThat(CaseStateFactory.forStatus(CaseStatus.CLOSED).isReadOnly()).isTrue();
    }

    @Test
    void factoryResolvesEveryStatus() {
        for (CaseStatus status : CaseStatus.values()) {
            assertThat(CaseStateFactory.forStatus(status).status()).isEqualTo(status);
        }
    }
}
