package com.discoveryhub.cases.messaging;

import com.discoveryhub.contracts.AuditEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Factory (creational) for the {@link AuditEvent}s the case-service emits (FR-7). The emitter
 * decides what happened (P5 records, it does not infer); refusals — a closed case refusing a new
 * evidence item — are audited as refusals, not as successes.
 *
 * <p>{@code eventId} is deterministic where it can be, derived from action + subject + occurredAt,
 * so a Kafka replay does not double the audit rows (matching the storage-service convention).
 * {@code service} is {@code "CASE"} to distinguish case-service events from hold-service events in
 * the cross-system audit trail even though both descend from the original P4.
 */
@Component
public final class CaseAuditEvents {

    private static final String SERVICE = "CASE";
    private static final String ACTOR = "investigator";

    public AuditEvent caseCreated(String caseId, String name) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", name);
        return event("case.created", AuditEvent.Outcome.SUCCESS, "case", caseId, caseId, detail);
    }

    public AuditEvent caseUpdated(String caseId, String field) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("field", field);
        return event("case.updated", AuditEvent.Outcome.SUCCESS, "case", caseId, caseId, detail);
    }

    public AuditEvent caseTransitioned(String caseId, String from, String to) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("from", from);
        detail.put("to", to);
        return event("case.transitioned", AuditEvent.Outcome.SUCCESS, "case", caseId, caseId, detail);
    }

    public AuditEvent caseClosed(String caseId) {
        return event("case.closed", AuditEvent.Outcome.SUCCESS, "case", caseId, caseId, Map.of());
    }

    public AuditEvent custodianAdded(String caseId, String custodianId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("custodianId", custodianId);
        return event("custodian.added", AuditEvent.Outcome.SUCCESS, "case", caseId, caseId, detail);
    }

    public AuditEvent evidenceAdded(String caseId, String messageId, String source) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("messageId", messageId);
        detail.put("source", source);
        return event("evidence.added", AuditEvent.Outcome.SUCCESS, "case", caseId, caseId, detail);
    }

    public AuditEvent evidenceRemoved(String caseId, String messageId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("messageId", messageId);
        return event("evidence.removed", AuditEvent.Outcome.SUCCESS, "case", caseId, caseId, detail);
    }

    public AuditEvent mutationRefused(String caseId, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        return event("case.mutation-refused", AuditEvent.Outcome.REFUSED, "case", caseId, caseId, detail);
    }

    private AuditEvent event(String action, AuditEvent.Outcome outcome, String subjectType,
                             String subjectId, String correlationId, Map<String, String> detail) {
        Instant occurredAt = Instant.now();
        String eventId = UUID.nameUUIDFromBytes(
                (action + "|" + subjectId + "|" + occurredAt).getBytes(StandardCharsets.UTF_8)).toString();
        return new AuditEvent(eventId, occurredAt, SERVICE, action, outcome,
                subjectType, subjectId, ACTOR, correlationId, detail);
    }
}
