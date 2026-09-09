package com.discoveryhub.disposition.api;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.archive.ArchiveGateway;
import com.discoveryhub.disposition.config.DispositionProperties;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import com.discoveryhub.disposition.hold.ActiveHold;
import com.discoveryhub.disposition.hold.CaseHoldClient;
import com.discoveryhub.disposition.hold.HoldCheckClient;
import com.discoveryhub.disposition.hold.HoldContext;
import com.discoveryhub.disposition.repository.DispositionItemRepository;
import com.discoveryhub.disposition.repository.DispositionRunRepository;
import com.discoveryhub.disposition.run.RetentionPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * {@code GET /disposition/stats/candidates} promises to answer "what would the next sweep touch?"
 * (FR-8.2). Before this test existed, {@code wouldBeDeleted} only subtracted guards 1-3 (hold
 * flag, hold scope, case evidence) and never ran guard 4 (the per-message P4 check), so it
 * overstated the blast radius whenever P4 would refuse something the other three guards missed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsControllerTest {

    @Mock ArchiveGateway archive;
    @Mock RetentionPolicyService retention;
    @Mock CaseHoldClient caseHolds;
    @Mock HoldCheckClient holdCheck;
    @Mock DispositionRunRepository runs;
    @Mock DispositionItemRepository items;

    private DispositionProperties props;
    private StatsController controller;

    @BeforeEach
    void setUp() {
        props = new DispositionProperties(DispositionProperties.DeleteMode.ARCHIVE_DB, 500,
                new DispositionProperties.HoldCheck(true, true, Duration.ofSeconds(1)),
                new DispositionProperties.Schedule(false, "0 0 0 * * *"));
        controller = new StatsController(archive, retention, caseHolds, holdCheck, runs, items, props);
        when(retention.cutoffs(any())).thenReturn(Map.of(
                MessageType.EMAIL, Instant.now().minus(Duration.ofMinutes(2))));
    }

    private ArchiveCandidate candidate(String id, boolean onHold) {
        return new ArchiveCandidate("msg-" + id, id, "custodian-1",
                MessageType.EMAIL, Instant.now().minus(Duration.ofMinutes(10)), onHold);
    }

    @Test
    void wouldBeDeletedExcludesAMessageThatOnlyP4KnowsIsHeld() {
        // Guards 1-3 all say "not protected"; only the per-message P4 check (guard 4) catches it.
        ArchiveCandidate candidate = candidate("EXCH-1", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(), Map.of()));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.HELD);

        Map<String, Object> body = controller.candidates();

        assertThat(body.get("candidatesInNextSweep")).isEqualTo(1);
        assertThat(body.get("wouldBeDeleted")).isEqualTo(0L);
        assertThat(body.get("protectedByHoldCheck")).isEqualTo(1L);
    }

    @Test
    void wouldBeDeletedCountsAMessageP4ConfirmsIsNotHeld() {
        ArchiveCandidate candidate = candidate("EXCH-2", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(), Map.of()));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.NOT_HELD);

        Map<String, Object> body = controller.candidates();

        assertThat(body.get("wouldBeDeleted")).isEqualTo(1L);
    }

    @Test
    void anUnknownP4VerdictIsTreatedAsProtectedWhenHoldCheckIsRequired() {
        ArchiveCandidate candidate = candidate("EXCH-3", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(List.of(), Map.of()));
        when(holdCheck.check(candidate.messageId())).thenReturn(HoldCheckClient.Verdict.UNKNOWN);

        Map<String, Object> body = controller.candidates();

        assertThat(body.get("wouldBeDeleted")).isEqualTo(0L);
        assertThat(body.get("protectedByHoldCheck")).isEqualTo(1L);
    }

    @Test
    void guard4NeverRunsForAMessageAlreadyProtectedByHoldScope() {
        ArchiveCandidate candidate = candidate("EXCH-4", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.of(
                List.of(new ActiveHold("hold-1", "case-1", "Matter", Set.of("custodian-1"), null, null, List.of())),
                Map.of()));

        Map<String, Object> body = controller.candidates();

        assertThat(body.get("protectedByHoldScope")).isEqualTo(1L);
        assertThat(body.get("wouldBeDeleted")).isEqualTo(0L);
        org.mockito.Mockito.verify(holdCheck, org.mockito.Mockito.never()).check(any());
    }

    @Test
    void everythingIsProtectedWhenTheHoldContextIsUnavailableAndCheckIsRequired() {
        ArchiveCandidate candidate = candidate("EXCH-5", false);
        when(archive.findCandidates(any(), anyInt())).thenReturn(List.of(candidate));
        when(caseHolds.contextFor(any())).thenReturn(HoldContext.unavailable());

        Map<String, Object> body = controller.candidates();

        assertThat(body.get("wouldBeDeleted")).isEqualTo(0L);
        org.mockito.Mockito.verify(holdCheck, org.mockito.Mockito.never()).check(any());
    }
}
