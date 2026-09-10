package com.discoveryhub.holds.service;

import com.discoveryhub.holds.api.ActiveHoldResponse;
import com.discoveryhub.holds.api.EvidenceCheckResponse;
import com.discoveryhub.holds.client.CaseEvidenceClient;
import com.discoveryhub.holds.client.CaseStatusClient;
import com.discoveryhub.holds.client.EvidenceLookupItem;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.repository.HoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P2.2's two run-level case guards (DISPOSITION.md's "What P4 has to provide"): every ACTIVE
 * hold's scope, and which of a set of messageIds are evidence in a held case.
 */
@ExtendWith(MockitoExtension.class)
class HoldCaseGuardServiceTest {

    @Mock HoldRepository holds;
    @Mock CaseStatusClient caseStatus;
    @Mock CaseEvidenceClient caseEvidence;

    private HoldCaseGuardService service;

    @BeforeEach
    void setUp() {
        service = new HoldCaseGuardService(holds, caseStatus, caseEvidence);
    }

    private HoldEntity activeHold(String holdId, String caseId, List<String> custodians) {
        HoldEntity h = new HoldEntity(holdId, caseId, HoldStatus.ACTIVE, Instant.now());
        h.applyScope(new HoldScope(custodians, null, null, null));
        return h;
    }

    // ---------------------------------------------------------- /holds/active

    @Test
    void activeHoldsReportsScopeAndBestEffortCaseName() {
        HoldEntity hold = activeHold("hold-1", "case-1", List.of("cust-004", "cust-017"));
        when(holds.findByStatus(HoldStatus.ACTIVE)).thenReturn(List.of(hold));
        when(caseStatus.caseName("case-1")).thenReturn(Optional.of("SEC Inquiry 2026"));

        List<ActiveHoldResponse> result = service.activeHolds();

        assertThat(result).hasSize(1);
        ActiveHoldResponse response = result.get(0);
        assertThat(response.holdId()).isEqualTo("hold-1");
        assertThat(response.caseId()).isEqualTo("case-1");
        assertThat(response.caseName()).isEqualTo("SEC Inquiry 2026");
        assertThat(response.custodianIds()).containsExactly("cust-004", "cust-017");
    }

    @Test
    void activeHoldsToleratesACaseNameLookupFailure() {
        HoldEntity hold = activeHold("hold-1", "case-1", List.of("cust-1"));
        when(holds.findByStatus(HoldStatus.ACTIVE)).thenReturn(List.of(hold));
        when(caseStatus.caseName("case-1")).thenReturn(Optional.empty());

        List<ActiveHoldResponse> result = service.activeHolds();

        // A missing name never fails the guard — it is cosmetic, not load-bearing.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).caseName()).isNull();
    }

    @Test
    void noActiveHoldsIsAnEmptyListNotAFailure() {
        when(holds.findByStatus(HoldStatus.ACTIVE)).thenReturn(List.of());

        assertThat(service.activeHolds()).isEmpty();
    }

    // ---------------------------------------------------------- /holds/evidence-check

    @Test
    void evidenceCheckSkipsTheCaseServiceCallWhenNoHoldsAreActive() {
        when(holds.findByStatus(HoldStatus.ACTIVE)).thenReturn(List.of());

        List<EvidenceCheckResponse> result = service.evidenceCheck(List.of("msg-1"));

        assertThat(result).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(caseEvidence);
    }

    @Test
    void evidenceCheckIncludesOnlyItemsWhoseCaseIsUnderAnActiveHold() {
        when(holds.findByStatus(HoldStatus.ACTIVE)).thenReturn(
                List.of(activeHold("hold-1", "case-1", List.of("cust-1"))));
        when(caseEvidence.lookupEvidence(List.of("msg-1", "msg-2"))).thenReturn(List.of(
                new EvidenceLookupItem("case-1", "msg-1"),
                // case-2 has no active hold, so this one must not appear in the result.
                new EvidenceLookupItem("case-2", "msg-2")));
        when(caseStatus.caseName("case-1")).thenReturn(Optional.of("SEC Inquiry 2026"));

        List<EvidenceCheckResponse> result = service.evidenceCheck(List.of("msg-1", "msg-2"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).messageId()).isEqualTo("msg-1");
        assertThat(result.get(0).holdId()).isEqualTo("hold-1");
        assertThat(result.get(0).caseName()).isEqualTo("SEC Inquiry 2026");
    }

    @Test
    void evidenceCheckPropagatesACaseServiceFailureRatherThanReportingAnEmptyAnswer() {
        when(holds.findByStatus(HoldStatus.ACTIVE)).thenReturn(
                List.of(activeHold("hold-1", "case-1", List.of("cust-1"))));
        when(caseEvidence.lookupEvidence(any())).thenThrow(new RuntimeException("case-service unreachable"));

        // No safe default exists for this guard — swallowing the failure into List.of() would
        // tell a disposition sweep "nothing is evidence" when the truth is "could not be asked".
        assertThatThrownBy(() -> service.evidenceCheck(List.of("msg-1")))
                .isInstanceOf(RuntimeException.class);
    }
}
