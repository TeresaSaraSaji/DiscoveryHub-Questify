package com.discoveryhub.holds.api;

import com.discoveryhub.contracts.HoldCheckResponse;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.service.HoldCaseGuardService;
import com.discoveryhub.holds.service.HoldCheckService;
import com.discoveryhub.holds.service.HoldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Hold-management API (FR-4). One controller, one responsibility: translate HTTP into service calls.
 *
 * <pre>
 * POST   /holds                       place a hold (202, async — returns RESOLVING hold)
 * GET    /holds/{id}                   hold detail
 * GET    /holds?caseId=&status=        list/filter holds
 * POST   /holds/{id}/release          release a hold (synchronous)
 * GET    /holds/check?messageId=...    is this message held? (FR-4.2 — the disposition guard)
 * GET    /holds/active                 every ACTIVE hold's scope — P2.2's case-level guard
 * POST   /holds/evidence-check         held-case evidence membership — P2.2's other case-level guard
 * GET    /holds/case/{caseId}/count   total held messages for a case (FR-4.4)
 * GET    /holds/stats                  dashboard counts
 * </pre>
 *
 * <p>{@code GET /holds/check} is the single most important endpoint in the system: it is what P2
 * asks before deleting a message, so it is the fail-closed guard against evidence destruction.
 * {@code /active} and {@code /evidence-check} are P2.2's run-level counterparts to it — see
 * {@code disposition-service/DISPOSITION.md}, "What P4 has to provide".
 */
@RestController
@RequestMapping("/holds")
public class HoldController {

    private final HoldService holdService;
    private final HoldCheckService checkService;
    private final HoldCaseGuardService caseGuard;

    public HoldController(HoldService holdService, HoldCheckService checkService, HoldCaseGuardService caseGuard) {
        this.holdService = holdService;
        this.checkService = checkService;
        this.caseGuard = caseGuard;
    }

    @PostMapping
    public ResponseEntity<HoldEntity> place(@RequestBody PlaceHoldRequest request) {
        HoldEntity hold = holdService.placeHold(request.caseId(), request.toScope());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(hold);
    }

    @GetMapping("/{id}")
    public HoldEntity get(@PathVariable("id") String id) {
        return holdService.getHold(id);
    }

    @GetMapping
    public List<HoldEntity> list(
            @RequestParam(name = "caseId", required = false) String caseId,
            @RequestParam(name = "status", required = false) HoldStatus status) {
        if (caseId != null && !caseId.isBlank()) {
            return holdService.listHoldsForCase(caseId);
        }
        if (status != null) {
            return holdService.listHoldsByStatus(status);
        }
        return holdService.listHoldsByStatus(HoldStatus.ACTIVE);
    }

    @PostMapping("/{id}/release")
    public HoldEntity release(@PathVariable("id") String id, @RequestBody(required = false) ReleaseRequest request) {
        String reason = request == null ? null : request.reason();
        return holdService.releaseHold(id, reason);
    }

    @GetMapping("/check")
    public HoldCheckResponse check(@RequestParam("messageId") String messageId) {
        return checkService.check(messageId);
    }

    /**
     * P2.2's active-hold-scope guard (DISPOSITION.md guard 2): every ACTIVE hold's scope, not the
     * messages it has been expanded to — closes the window between "hold placed" and "propagation
     * finished" that every per-message guard is blind to.
     */
    @GetMapping("/active")
    public List<ActiveHoldResponse> active() {
        return caseGuard.activeHolds();
    }

    /**
     * P2.2's held-case evidence guard (DISPOSITION.md guard 3): of these messageIds, which are
     * evidence items in a case under an active hold, regardless of whether that hold's own
     * custodian/date scope would cover them. A case-service failure propagates as a non-2xx
     * response rather than an empty array — see {@link com.discoveryhub.holds.client.CaseEvidenceClient}.
     */
    @PostMapping("/evidence-check")
    public List<EvidenceCheckResponse> evidenceCheck(@RequestBody EvidenceCheckRequest request) {
        return caseGuard.evidenceCheck(request.messageIds());
    }

    @GetMapping("/case/{caseId}/count")
    public Map<String, Object> heldCount(@PathVariable("caseId") String caseId) {
        return Map.of("caseId", caseId, "heldMessages", holdService.heldMessageCountForCase(caseId));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return holdService.stats();
    }
}
