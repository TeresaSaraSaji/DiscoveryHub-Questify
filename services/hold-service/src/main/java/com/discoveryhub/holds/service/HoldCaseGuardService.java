package com.discoveryhub.holds.service;

import com.discoveryhub.holds.api.ActiveHoldResponse;
import com.discoveryhub.holds.api.EvidenceCheckResponse;
import com.discoveryhub.holds.client.CaseEvidenceClient;
import com.discoveryhub.holds.client.CaseStatusClient;
import com.discoveryhub.holds.client.EvidenceLookupItem;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.repository.HoldRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The two case-level guards {@code run.DispositionService} on P2.2 asks once per sweep, not once
 * per candidate (DISPOSITION.md's "What P4 has to provide"): {@code GET /holds/active} and
 * {@code POST /holds/evidence-check}. Both read {@code holds} directly rather than through
 * {@link HoldService}, because both are read-only fan-out over ACTIVE holds, not lifecycle
 * operations.
 */
@Service
public class HoldCaseGuardService {

    private final HoldRepository holds;
    private final CaseStatusClient caseStatus;
    private final CaseEvidenceClient caseEvidence;

    public HoldCaseGuardService(HoldRepository holds, CaseStatusClient caseStatus,
                                CaseEvidenceClient caseEvidence) {
        this.holds = holds;
        this.caseStatus = caseStatus;
        this.caseEvidence = caseEvidence;
    }

    /**
     * Every ACTIVE hold's scope. Case names are looked up one case at a time — deliberately: there
     * are only ever a handful of active holds in force, so this is a handful of calls, and a
     * failed lookup for one case must not blank out the others.
     */
    @Transactional(readOnly = true)
    public List<ActiveHoldResponse> activeHolds() {
        List<HoldEntity> active = holds.findByStatus(HoldStatus.ACTIVE);
        Map<String, String> caseNames = new LinkedHashMap<>();
        for (HoldEntity hold : active) {
            caseNames.computeIfAbsent(hold.getCaseId(), this::caseNameOrNull);
        }
        return active.stream()
                .map(hold -> new ActiveHoldResponse(
                        hold.getHoldId(),
                        hold.getCaseId(),
                        caseNames.get(hold.getCaseId()),
                        hold.custodianList(),
                        hold.getDateFrom(),
                        hold.getDateTo(),
                        hasTerms(hold) ? List.of(hold.getSearchTerms()) : List.of()))
                .toList();
    }

    /**
     * Of the given messageIds, which are evidence in a case with at least one ACTIVE hold.
     *
     * <p>Lets a case-service failure propagate (see {@link CaseEvidenceClient}): this guard has no
     * safe default, so {@code HoldController} turns that into a non-2xx response rather than this
     * method inventing an empty — and therefore falsely reassuring — answer.
     */
    @Transactional(readOnly = true)
    public List<EvidenceCheckResponse> evidenceCheck(List<String> messageIds) {
        List<HoldEntity> active = holds.findByStatus(HoldStatus.ACTIVE);
        if (active.isEmpty() || messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        // First active hold per case: the evidence guard only needs to name *a* hold protecting
        // the item, not every hold on the case.
        Map<String, String> holdIdByCase = new LinkedHashMap<>();
        for (HoldEntity hold : active) {
            holdIdByCase.putIfAbsent(hold.getCaseId(), hold.getHoldId());
        }
        Set<String> heldCaseIds = holdIdByCase.keySet();

        List<EvidenceLookupItem> found = caseEvidence.lookupEvidence(messageIds);
        Map<String, String> caseNames = new LinkedHashMap<>();
        List<EvidenceCheckResponse> result = new ArrayList<>();
        for (EvidenceLookupItem item : found) {
            if (!heldCaseIds.contains(item.caseId())) {
                continue;
            }
            String caseName = caseNames.computeIfAbsent(item.caseId(), this::caseNameOrNull);
            result.add(new EvidenceCheckResponse(
                    item.messageId(), holdIdByCase.get(item.caseId()), item.caseId(), caseName));
        }
        return result;
    }

    private static boolean hasTerms(HoldEntity hold) {
        return hold.getSearchTerms() != null && !hold.getSearchTerms().isBlank();
    }

    private String caseNameOrNull(String caseId) {
        return caseStatus.caseName(caseId).orElse(null);
    }
}
