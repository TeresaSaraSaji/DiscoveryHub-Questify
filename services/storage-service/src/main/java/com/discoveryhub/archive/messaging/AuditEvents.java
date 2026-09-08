package com.discoveryhub.archive.messaging;

import com.discoveryhub.contracts.AuditEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the {@link AuditEvent}s P2 emits. The emitter decides what happened (P5 records, it does
 * not infer), and refusals are audited as refusals — a disposition that refused to delete a held
 * message is the single most important event in the system and is not a success (AuditEvent.java).
 *
 * <p>{@code eventId} is deterministic where it can be: derived from action + subject + occurredAt,
 * so a Kafka replay does not double the audit rows.
 */
@Component
public final class AuditEvents {

    private static final String SERVICE = "P2";
    private static final String ACTOR = "system";

    public AuditEvent archived(String messageId, int attachmentCount) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("attachmentCount", String.valueOf(attachmentCount));
        return event("message.archived", AuditEvent.Outcome.SUCCESS, "message", messageId, messageId, detail);
    }

    public AuditEvent deduped(String externalId) {
        return event("message.deduped", AuditEvent.Outcome.SUCCESS, "message", externalId, externalId, Map.of());
    }

    public AuditEvent dispositionDeleted(String runId, String messageId, String externalId, String custodianId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("runId", runId);
        detail.put("externalId", externalId);
        detail.put("custodianId", custodianId);
        return event("disposition.deleted", AuditEvent.Outcome.SUCCESS, "message", messageId, runId, detail);
    }

    public AuditEvent dispositionRefused(String runId, String messageId, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("runId", runId);
        detail.put("reason", reason);
        return event("disposition.refused", AuditEvent.Outcome.REFUSED, "message", messageId, runId, detail);
    }

    public AuditEvent dispositionRunFailed(String runId, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        // Run-level failure: no single subject id, so key the dedupe on the run + time.
        return event("disposition.run-failed", AuditEvent.Outcome.FAILURE, "disposition-run", runId, runId, detail);
    }

    public AuditEvent holdUpdated(String messageId, boolean held, String caseId) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("held", String.valueOf(held));
        if (caseId != null) {
            detail.put("caseId", caseId);
        }
        return event("message.hold-updated", AuditEvent.Outcome.SUCCESS, "message", messageId, messageId, detail);
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
