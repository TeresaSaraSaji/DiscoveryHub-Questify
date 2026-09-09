package com.discoveryhub.holds.api;

import com.discoveryhub.contracts.HoldCheckResponse;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
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
 * GET    /holds/case/{caseId}/count   total held messages for a case (FR-4.4)
 * GET    /holds/stats                  dashboard counts
 * </pre>
 *
 * <p>{@code GET /holds/check} is the single most important endpoint in the system: it is what P2
 * asks before deleting a message, so it is the fail-closed guard against evidence destruction.
 */
@RestController
@RequestMapping("/holds")
public class HoldController {

    private final HoldService holdService;
    private final HoldCheckService checkService;

    public HoldController(HoldService holdService, HoldCheckService checkService) {
        this.holdService = holdService;
        this.checkService = checkService;
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

    @GetMapping("/case/{caseId}/count")
    public Map<String, Object> heldCount(@PathVariable("caseId") String caseId) {
        return Map.of("caseId", caseId, "heldMessages", holdService.heldMessageCountForCase(caseId));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return holdService.stats();
    }
}
