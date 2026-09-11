package com.discoveryhub.holds.messaging;

import com.discoveryhub.contracts.AuditEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Factory (creational) for the {@link AuditEvent}s the hold-service emits (FR-7). The emitter
 * decides what happened; refusals — a hold that refused to release because... — are audited as
 * refusals, and a failed placement is audited as a failure, matching the contract's intent that
 * the single most important events are not silently successes.
 *
 * <p>{@code eventId} is deterministic where it can be, derived from action + subject + occurredAt,
 * so a Kafka replay does not double the audit rows (matching the case- and storage-service
 * conventions). {@code service} is {@code "HOLD"} to distinguish hold-service events in the
 * cross-system audit trail.
 */
@Component
public final class HoldAuditEvents {

    private static final String SERVICE = "HOLD";
    private static final String ACTOR = "investigator";

    public AuditEvent holdPlaced(String holdId, String caseId, int messageCount) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("caseId", caseId);
        detail.put("messageCount", String.valueOf(messageCount));
        return event("hold.placed", AuditEvent.Outcome.SUCCESS, "hold", holdId, holdId, detail);
    }

    public AuditEvent holdReleased(String holdId, String caseId, String reason) {
        return holdReleased(holdId, caseId, reason, -1, -1);
    }

    /**
     * Release audit carrying the overlap split (FR-4.5): how many of the hold's messages actually
     * stopped being protected, and how many stayed evidence because another active hold still
     * covers them. Without both numbers the audit trail cannot answer "why is message 123 still
     * held after we released the hold on it?" — the answer is in {@code stillHeldMessages}, and an
     * auditor who can only see the covered total has to reconstruct it by hand.
     *
     * <p>Counts below zero are omitted, which is how the {@code (holdId, caseId, reason)} overload
     * says "this caller did not compute the split".
     */
    public AuditEvent holdReleased(String holdId, String caseId, String reason,
                                   int unprotectedMessages, int stillHeldMessages) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("caseId", caseId);
        if (reason != null) {
            detail.put("reason", reason);
        }
        if (unprotectedMessages >= 0) {
            detail.put("unprotectedMessages", String.valueOf(unprotectedMessages));
        }
        if (stillHeldMessages >= 0) {
            detail.put("stillHeldMessages", String.valueOf(stillHeldMessages));
        }
        return event("hold.released", AuditEvent.Outcome.SUCCESS, "hold", holdId, holdId, detail);
    }

    public AuditEvent holdFailed(String holdId, String caseId, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("caseId", caseId);
        detail.put("reason", reason);
        return event("hold.failed", AuditEvent.Outcome.FAILURE, "hold", holdId, holdId, detail);
    }

    public AuditEvent holdCheckAnswered(String messageId, boolean held) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("held", String.valueOf(held));
        return event("hold.check", AuditEvent.Outcome.SUCCESS, "message", messageId, messageId, detail);
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
