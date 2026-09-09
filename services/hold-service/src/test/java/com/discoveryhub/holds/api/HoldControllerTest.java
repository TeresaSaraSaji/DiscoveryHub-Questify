package com.discoveryhub.holds.api;

import com.discoveryhub.contracts.HoldCheckResponse;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.service.HoldCaseGuardService;
import com.discoveryhub.holds.service.HoldCheckService;
import com.discoveryhub.holds.service.HoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Controller-layer tests for the hold-service API (FR-4). The controller is tested directly with a
 * mocked service layer — no Spring context — matching the team's {@code IngestControllerTest}
 * pattern. Covers the critical {@code GET /holds/check} endpoint (the disposition guard), the
 * 202 Accepted placement, release, listing, filtering, and error paths.
 */
@ExtendWith(MockitoExtension.class)
class HoldControllerTest {

    @Mock HoldService holdService;
    @Mock HoldCheckService checkService;
    @Mock HoldCaseGuardService caseGuard;

    private HoldController controller;

    @BeforeEach
    void setUp() {
        controller = new HoldController(holdService, checkService, caseGuard);
    }

    private HoldEntity hold(String id, HoldStatus status) {
        HoldEntity h = new HoldEntity(id, "case-1", status, Instant.now());
        h.applyScope(new HoldScope(List.of("cust-1"), null, null, null));
        return h;
    }

    // ---------------------------------------------------------- place

    @Test
    void placeReturns202AcceptedWithResolvingHold() {
        HoldEntity placed = hold("hold-1", HoldStatus.RESOLVING);
        when(holdService.placeHold(eq("case-1"), any())).thenReturn(placed);

        ResponseEntity<HoldEntity> response = controller.place(
                new PlaceHoldRequest("case-1", List.of("cust-1"), null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().getHoldId()).isEqualTo("hold-1");
        assertThat(response.getBody().getStatus()).isEqualTo(HoldStatus.RESOLVING);
    }

    @Test
    void placePropagatesConflictForClosedCase() {
        when(holdService.placeHold(any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "case is closed"));

        assertThatThrownBy(() -> controller.place(
                new PlaceHoldRequest("case-1", List.of("cust-1"), null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void placeConvertsRequestToScopeWithAllFields() {
        when(holdService.placeHold(any(), any())).thenReturn(hold("hold-1", HoldStatus.RESOLVING));

        controller.place(new PlaceHoldRequest("case-1",
                List.of("cust-a", "cust-b"),
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-12-31T23:59:59Z"),
                "trade secret"));

        verify(holdService).placeHold(eq("case-1"), eq(new HoldScope(
                List.of("cust-a", "cust-b"),
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-12-31T23:59:59Z"),
                "trade secret")));
    }

    // ---------------------------------------------------------- get

    @Test
    void getReturnsHoldWhenFound() {
        when(holdService.getHold("hold-1")).thenReturn(hold("hold-1", HoldStatus.ACTIVE));

        HoldEntity result = controller.get("hold-1");

        assertThat(result.getHoldId()).isEqualTo("hold-1");
    }

    @Test
    void getThrowsNotFoundWhenHoldMissing() {
        when(holdService.getHold("missing")).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "hold not found: missing"));

        assertThatThrownBy(() -> controller.get("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------------------------------------------------------- list

    @Test
    void listByCaseId() {
        when(holdService.listHoldsForCase("case-1")).thenReturn(
                List.of(hold("h1", HoldStatus.ACTIVE), hold("h2", HoldStatus.RELEASED)));

        List<HoldEntity> result = controller.list("case-1", null);

        assertThat(result).hasSize(2);
    }

    @Test
    void listByStatus() {
        when(holdService.listHoldsByStatus(HoldStatus.ACTIVE)).thenReturn(
                List.of(hold("h1", HoldStatus.ACTIVE)));

        List<HoldEntity> result = controller.list(null, HoldStatus.ACTIVE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    void listDefaultsToActiveWhenNoFilter() {
        when(holdService.listHoldsByStatus(HoldStatus.ACTIVE)).thenReturn(List.of());

        controller.list(null, null);

        verify(holdService).listHoldsByStatus(HoldStatus.ACTIVE);
    }

    @Test
    void listPrefersCaseIdOverStatusWhenBothPresent() {
        when(holdService.listHoldsForCase("case-1")).thenReturn(List.of());

        controller.list("case-1", HoldStatus.RELEASED);

        verify(holdService).listHoldsForCase("case-1");
    }

    @Test
    void listIgnoresBlankCaseId() {
        when(holdService.listHoldsByStatus(HoldStatus.ACTIVE)).thenReturn(List.of());

        controller.list("  ", null);

        verify(holdService).listHoldsByStatus(HoldStatus.ACTIVE);
    }

    // ---------------------------------------------------------- release

    @Test
    void releaseReturnsReleasedHold() {
        HoldEntity released = hold("hold-1", HoldStatus.RELEASED);
        when(holdService.releaseHold(eq("hold-1"), eq("manual"))).thenReturn(released);

        HoldEntity result = controller.release("hold-1", new ReleaseRequest("manual"));

        assertThat(result.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    void releaseWithNullBodyDefaultsReasonToNull() {
        HoldEntity released = hold("hold-1", HoldStatus.RELEASED);
        when(holdService.releaseHold(eq("hold-1"), eq(null))).thenReturn(released);

        HoldEntity result = controller.release("hold-1", null);

        assertThat(result.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }

    // ---------------------------------------------------------- check (the disposition guard)

    @Test
    void checkReturnsHeldTrue() {
        when(checkService.check("msg-1")).thenReturn(new HoldCheckResponse(true));

        HoldCheckResponse result = controller.check("msg-1");

        assertThat(result.held()).isTrue();
    }

    @Test
    void checkReturnsHeldFalse() {
        when(checkService.check("msg-2")).thenReturn(new HoldCheckResponse(false));

        HoldCheckResponse result = controller.check("msg-2");

        assertThat(result.held()).isFalse();
    }

    // ---------------------------------------------------------- P2.2's case-level guards

    @Test
    void activeDelegatesToCaseGuardService() {
        ActiveHoldResponse hold = new ActiveHoldResponse(
                "hold-1", "case-1", "SEC Inquiry", List.of("cust-1"), null, null, List.of());
        when(caseGuard.activeHolds()).thenReturn(List.of(hold));

        List<ActiveHoldResponse> result = controller.active();

        assertThat(result).containsExactly(hold);
    }

    @Test
    void evidenceCheckDelegatesToCaseGuardService() {
        EvidenceCheckResponse item = new EvidenceCheckResponse("msg-1", "hold-1", "case-1", "SEC Inquiry");
        when(caseGuard.evidenceCheck(List.of("msg-1", "msg-2"))).thenReturn(List.of(item));

        List<EvidenceCheckResponse> result = controller.evidenceCheck(
                new EvidenceCheckRequest(List.of("msg-1", "msg-2")));

        assertThat(result).containsExactly(item);
    }

    // ---------------------------------------------------------- held count per case

    @Test
    void heldCountReturnsCountForCase() {
        when(holdService.heldMessageCountForCase("case-1")).thenReturn(42L);

        Map<String, Object> result = controller.heldCount("case-1");

        assertThat(result).containsEntry("caseId", "case-1");
        assertThat(result).containsEntry("heldMessages", 42L);
    }

    // ---------------------------------------------------------- stats

    @Test
    void statsReturnsHoldCounts() {
        when(holdService.stats()).thenReturn(Map.of(
                "activeHolds", 3L, "resolvingHolds", 1L,
                "releasedHolds", 2L, "failedHolds", 0L));

        Map<String, Object> result = controller.stats();

        assertThat(result).containsEntry("activeHolds", 3L);
        assertThat(result).containsEntry("resolvingHolds", 1L);
    }
}
