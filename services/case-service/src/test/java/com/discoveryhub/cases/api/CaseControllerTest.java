package com.discoveryhub.cases.api;

import com.discoveryhub.cases.config.CaseProperties;
import com.discoveryhub.cases.domain.CaseCustodianEntity;
import com.discoveryhub.cases.domain.CaseEntity;
import com.discoveryhub.cases.domain.CaseStatus;
import com.discoveryhub.cases.domain.EvidenceEntity;
import com.discoveryhub.cases.domain.EvidenceSource;
import com.discoveryhub.cases.domain.MatterType;
import com.discoveryhub.cases.service.CaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
 * Controller-layer tests for the case-service API (FR-2). The controller is tested directly with a
 * mocked {@link CaseService} — no Spring context, no MockMvc — matching the team's pattern in
 * {@code IngestControllerTest}. Every endpoint, every error path, and pagination capping are
 * exercised so the HTTP contract is pinned without the service logic (which has its own tests).
 */
@ExtendWith(MockitoExtension.class)
class CaseControllerTest {

    @Mock CaseService service;

    private CaseController controller;

    @BeforeEach
    void setUp() {
        controller = new CaseController(service, new CaseProperties(50, 200));
    }

    private CaseEntity entity(String id, CaseStatus status) {
        return new CaseEntity(id, "name", "desc", MatterType.INVESTIGATION, "owner", status, Instant.now());
    }

    // ---------------------------------------------------------- create

    @Test
    void createReturns201WithCreatedCase() {
        CaseEntity created = entity("case-1", CaseStatus.DRAFT);
        when(service.createCase(any())).thenReturn(created);

        ResponseEntity<CaseEntity> response = controller.create(
                new CaseRequest("name", "desc", MatterType.INVESTIGATION, "owner"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getCaseId()).isEqualTo("case-1");
        assertThat(response.getBody().getStatus()).isEqualTo(CaseStatus.DRAFT);
    }

    // ---------------------------------------------------------- list + pagination

    @Test
    void listReturnsPageOfCases() {
        Page<CaseEntity> page = new PageImpl<>(List.of(entity("c1", CaseStatus.DRAFT), entity("c2", CaseStatus.ACTIVE)));
        when(service.listCases(eq(null), any())).thenReturn(page);

        Page<CaseEntity> result = controller.list(null, 0, 50);

        assertThat(result.getContent()).hasSize(2);
    }

    @Test
    void listFiltersByStatus() {
        Page<CaseEntity> page = new PageImpl<>(List.of(entity("c1", CaseStatus.ACTIVE)));
        when(service.listCases(eq(CaseStatus.ACTIVE), any())).thenReturn(page);

        Page<CaseEntity> result = controller.list(CaseStatus.ACTIVE, 0, 50);

        assertThat(result.getContent()).hasSize(1);
        verify(service).listCases(eq(CaseStatus.ACTIVE), any());
    }

    @Test
    void listCapsPageSizeAtMax() {
        when(service.listCases(any(), any())).thenReturn(new PageImpl<>(List.of()));

        controller.list(null, 0, 10000);

        verify(service).listCases(eq(null), eq(PageRequest.of(0, 200)));
    }

    @Test
    void listFallsBackToDefaultPageSizeWhenSizeNotSupplied() {
        when(service.listCases(any(), any())).thenReturn(new PageImpl<>(List.of()));

        controller.list(null, 0, null);

        verify(service).listCases(eq(null), eq(PageRequest.of(0, 50)));
    }

    @Test
    void listClampsPageSizeToAtLeastOne() {
        when(service.listCases(any(), any())).thenReturn(new PageImpl<>(List.of()));

        controller.list(null, 0, 0);

        verify(service).listCases(eq(null), eq(PageRequest.of(0, 1)));
    }

    // ---------------------------------------------------------- stats (takes precedence over {id})

    @Test
    void statsEndpointReturnsCounts() {
        when(service.stats()).thenReturn(Map.of("totalCases", 5, "activeCases", 3, "closedCases", 2));

        Map<String, Object> result = controller.stats();

        assertThat(result).containsEntry("totalCases", 5);
        assertThat(result).containsEntry("activeCases", 3);
    }

    // ---------------------------------------------------------- get

    @Test
    void getReturnsCaseWhenFound() {
        when(service.getCase("case-1")).thenReturn(entity("case-1", CaseStatus.ACTIVE));

        CaseEntity result = controller.get("case-1");

        assertThat(result.getCaseId()).isEqualTo("case-1");
    }

    @Test
    void getThrowsNotFoundWhenCaseMissing() {
        when(service.getCase("missing")).thenThrow(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "case not found: missing"));

        assertThatThrownBy(() -> controller.get("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ---------------------------------------------------------- update

    @Test
    void updateReturnsUpdatedCase() {
        CaseEntity updated = entity("case-1", CaseStatus.ACTIVE);
        updated.setName("new name");
        when(service.updateCase(eq("case-1"), any())).thenReturn(updated);

        CaseEntity result = controller.update("case-1",
                new CaseRequest("new name", null, null, null));

        assertThat(result.getName()).isEqualTo("new name");
    }

    // ---------------------------------------------------------- transitions

    @Test
    void transitionReturnsTransitionedCase() {
        when(service.transition("case-1", CaseStatus.ACTIVE)).thenReturn(entity("case-1", CaseStatus.ACTIVE));

        CaseEntity result = controller.transition("case-1", new TransitionRequest(CaseStatus.ACTIVE));

        assertThat(result.getStatus()).isEqualTo(CaseStatus.ACTIVE);
    }

    // ---------------------------------------------------------- custodians

    @Test
    void addCustodianReturns201() {
        CaseCustodianEntity cust = new CaseCustodianEntity("case-1", "cust-1", Instant.now());
        when(service.addCustodian(eq("case-1"), any())).thenReturn(cust);

        ResponseEntity<Object> response = controller.addCustodian("case-1", new AddCustodianRequest("cust-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isInstanceOf(CaseCustodianEntity.class);
    }

    @Test
    void listCustodiansDelegatesToService() {
        when(service.listCustodians("case-1")).thenReturn(
                List.of(new CaseCustodianEntity("case-1", "cust-1", Instant.now())));

        List<CaseCustodianEntity> result = controller.custodians("case-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCustodianId()).isEqualTo("cust-1");
    }

    // ---------------------------------------------------------- evidence

    @Test
    void addEvidenceReturns201() {
        EvidenceEntity ev = new EvidenceEntity("case-1", "msg-1", EvidenceSource.MANUAL, null, null, Instant.now());
        when(service.addEvidence(eq("case-1"), any())).thenReturn(ev);

        ResponseEntity<Object> response = controller.addEvidence("case-1", new AddEvidenceRequest("msg-1", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addEvidenceBatchReturnsSummary() {
        when(service.addEvidenceBatch(eq("case-1"), any())).thenReturn(new BulkEvidenceResult(3, 2, 1, 0));

        BulkEvidenceResult result = controller.addEvidenceBatch("case-1",
                new AddEvidenceBatchRequest(List.of("m1", "m2", "m3"), EvidenceSource.SEARCH, "search-1"));

        assertThat(result.requested()).isEqualTo(3);
        assertThat(result.added()).isEqualTo(2);
        assertThat(result.alreadyPresent()).isEqualTo(1);
    }

    @Test
    void listEvidenceDelegatesToService() {
        when(service.listEvidence("case-1")).thenReturn(
                List.of(new EvidenceEntity("case-1", "msg-1", EvidenceSource.MANUAL, null, null, Instant.now())));

        List<EvidenceEntity> result = controller.evidence("case-1");

        assertThat(result).hasSize(1);
    }

    @Test
    void removeEvidenceReturns204() {
        ResponseEntity<Void> response = controller.removeEvidence("case-1", "msg-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).removeEvidence("case-1", "msg-1");
    }
}
