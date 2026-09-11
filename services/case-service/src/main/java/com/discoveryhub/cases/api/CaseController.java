package com.discoveryhub.cases.api;

import com.discoveryhub.cases.config.CaseProperties;
import com.discoveryhub.cases.domain.CaseCustodianEntity;
import com.discoveryhub.cases.domain.CaseEntity;
import com.discoveryhub.cases.domain.CaseStatus;
import com.discoveryhub.cases.domain.EvidenceEntity;
import com.discoveryhub.cases.service.CaseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Case-management API (FR-2). One controller, one responsibility: translate HTTP into service
 * calls. The lifecycle, read-only, and audit concerns live in {@link CaseService}; the controller
 * only frames requests and responses.
 *
 * <pre>
 * POST   /cases                            create a case (DRAFT)
 * GET    /cases?status=&page=&size=         list/filter cases
 * GET    /cases/{id}                        case detail
 * PATCH  /cases/{id}                        update name/description/owner/matterType
 * POST   /cases/{id}/transitions            transition status (DRAFT->ACTIVE->UNDER_REVIEW->CLOSED)
 * GET    /cases/{id}/custodians             list custodians
 * POST   /cases/{id}/custodians             attach a custodian
 * GET    /cases/{id}/evidence               list evidence items
 * POST   /cases/{id}/evidence               add one message as evidence
 * POST   /cases/{id}/evidence/batch         add a page of search results as evidence
 * DELETE /cases/{id}/evidence/{messageId}   remove an evidence item
 * POST   /cases/evidence/lookup             bulk evidence membership by messageId, any case
 * GET    /cases/stats                       dashboard counts (totalCases, activeCases, closedCases)
 * </pre>
 *
 * <p>{@code /cases/stats} and {@code /cases/evidence/lookup} are both literal paths and take
 * precedence over {@code /cases/{id}}, so neither is mistaken for a case whose id happens to
 * match.
 */
@RestController
@RequestMapping("/cases")
public class CaseController {

    private final CaseService service;
    private final CaseProperties props;

    public CaseController(CaseService service, CaseProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<CaseEntity> create(@RequestBody CaseRequest request) {
        CaseEntity created = service.createCase(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public Page<CaseEntity> list(
            @RequestParam(name = "status", required = false) CaseStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", required = false) Integer size) {
        int pageSize = cap(size == null ? props.defaultPageSize() : size);
        return service.listCases(status, PageRequest.of(Math.max(0, page), pageSize));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    /**
     * P4's held-case evidence guard for disposition (DISPOSITION.md): "of these messages, which
     * are evidence items, and on which case?" Answered here without regard to hold status —
     * hold-service (which calls this) is the one that knows which cases are under an active hold,
     * and filters this raw membership with that data.
     */
    @PostMapping("/evidence/lookup")
    public List<EvidenceLookupItem> lookupEvidence(@RequestBody EvidenceLookupRequest request) {
        return service.lookupEvidence(request.messageIds());
    }

    @GetMapping("/{id}")
    public CaseEntity get(@PathVariable("id") String id) {
        return service.getCase(id);
    }

    @PatchMapping("/{id}")
    public CaseEntity update(@PathVariable("id") String id, @RequestBody CaseRequest request) {
        return service.updateCase(id, request);
    }

    @PostMapping("/{id}/transitions")
    public CaseEntity transition(@PathVariable("id") String id, @RequestBody TransitionRequest request) {
        return service.transition(id, request.targetStatus());
    }

    @GetMapping("/{id}/custodians")
    public List<CaseCustodianEntity> custodians(@PathVariable("id") String id) {
        return service.listCustodians(id);
    }

    @PostMapping("/{id}/custodians")
    public ResponseEntity<Object> addCustodian(@PathVariable("id") String id, @RequestBody AddCustodianRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addCustodian(id, request));
    }

    @GetMapping("/{id}/evidence")
    public List<EvidenceEntity> evidence(@PathVariable("id") String id) {
        return service.listEvidence(id);
    }

    @PostMapping("/{id}/evidence")
    public ResponseEntity<Object> addEvidence(@PathVariable("id") String id, @RequestBody AddEvidenceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addEvidence(id, request));
    }

    @PostMapping("/{id}/evidence/batch")
    public BulkEvidenceResult addEvidenceBatch(@PathVariable("id") String id,
                                                @RequestBody AddEvidenceBatchRequest request) {
        return service.addEvidenceBatch(id, request);
    }

    @DeleteMapping("/{id}/evidence/{messageId}")
    public ResponseEntity<Void> removeEvidence(@PathVariable("id") String id,
                                               @PathVariable("messageId") String messageId) {
        service.removeEvidence(id, messageId);
        return ResponseEntity.noContent().build();
    }

    private int cap(int size) {
        return Math.max(1, Math.min(size, props.maxPageSize()));
    }
}
