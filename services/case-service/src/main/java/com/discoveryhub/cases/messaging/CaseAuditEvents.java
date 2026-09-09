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
 *
 * <p>{@code correlationId} is threaded in by the caller ({@link com.discoveryhub.cases.service.CaseService},
 * which generates one id per user action) rather than defaulted to {@code caseId} here — the
 * contract's whole point for {@code correlationId} is to tie every event one user action produces
 * across all five services together, and the matching {@link com.discoveryhub.contracts.CaseEvent}
 * on {@code cases.events} for the same action carries that same id. Defaulting to {@code caseId}
 * would make every event on the same case share one correlation id forever, instead of grouping
 * only the events from one action.
 */
@Component
public final class CaseAuditEvents {

    private static final String SERVICE = "CASE";
    private static final String ACTOR = "investigator";

    public AuditEvent caseCreated(String caseId, String name, String correlationId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("name", name);
        return event("case.created", AuditEvent.Outcome.SUCCESS, "case", caseId, correlationId, detail);
    }

    public AuditEvent caseUpdated(String caseId, String field, String correlationId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("field", field);
        return event("case.updated", AuditEvent.Outcome.SUCCESS, "case", caseId, correlationId, detail);
    }

    public AuditEvent caseTransitioned(String caseId, String from, String to, String correlationId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("from", from);
        detail.put("to", to);
        return event("case.transitioned", AuditEvent.Outcome.SUCCESS, "case", caseId, correlationId, detail);
    }

    public AuditEvent caseClosed(String caseId, String correlationId) {
        return event("case.closed", AuditEvent.Outcome.SUCCESS, "case", caseId, correlationId, Map.of());
    }

    public AuditEvent custodianAdded(String caseId, String custodianId, String correlationId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("custodianId", custodianId);
        return event("custodian.added", AuditEvent.Outcome.SUCCESS, "case", caseId, correlationId, detail, custodianId);
    }

    public AuditEvent evidenceAdded(String caseId, String messageId, String source, String correlationId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("messageId", messageId);
        detail.put("source", source);
        return event("evidence.added", AuditEvent.Outcome.SUCCESS, "case", caseId, correlationId, detail, messageId);
    }

    public AuditEvent evidenceRemoved(String caseId, String messageId, String correlationId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("messageId", messageId);
        return event("evidence.removed", AuditEvent.Outcome.SUCCESS, "case", caseId, correlationId, detail, messageId);
    }

    public AuditEvent mutationRefused(String caseId, String reason, String correlationId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        return event("case.mutation-refused", AuditEvent.Outcome.REFUSED, "case", caseId, correlationId, detail);
    }

    private AuditEvent event(String action, AuditEvent.Outcome outcome, String subjectType,
                             String subjectId, String correlationId, Map<String, String> detail) {
        return event(action, outcome, subjectType, subjectId, correlationId, detail, subjectId);
    }

    /**
     * @param dedupeKey an id specific to the item this event is about (e.g. a custodianId or
     *                  messageId), folded into {@code eventId} alongside {@code subjectId} so two
     *                  different items added to the same case in the same instant do not hash to
     *                  the same {@code eventId} and have one of them silently dropped as a
     *                  "duplicate" by P5's insert-or-ignore. Falls back to {@code subjectId} for
     *                  case-level actions that have no finer-grained item.
     */
    private AuditEvent event(String action, AuditEvent.Outcome outcome, String subjectType,
                             String subjectId, String correlationId, Map<String, String> detail,
                             String dedupeKey) {
        Instant occurredAt = Instant.now();
        String eventId = UUID.nameUUIDFromBytes(
                (action + "|" + subjectId + "|" + dedupeKey + "|" + occurredAt).getBytes(StandardCharsets.UTF_8))
                .toString();
        return new AuditEvent(eventId, occurredAt, SERVICE, action, outcome,
                subjectType, subjectId, ACTOR, correlationId, detail);
    }
}
