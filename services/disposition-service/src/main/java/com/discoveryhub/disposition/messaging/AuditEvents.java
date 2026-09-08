package com.discoveryhub.disposition.messaging;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.ArchiveCandidate;
import com.discoveryhub.disposition.hold.ActiveHold;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The {@link AuditEvent}s P2.2 emits (FR-7.1). The emitter decides what happened — P5 records, it
 * does not infer — and refusals are recorded as refusals.
 *
 * <p>{@code disposition.refused} is the most important event this service produces. A hold that
 * prevented a deletion is the evidence that the legal hold worked, and it is not a success, so it
 * carries {@link AuditEvent.Outcome#REFUSED} rather than being logged and lost.
 *
 * <p>{@code eventId} is derived from action, subject and timestamp so a Kafka replay does not
 * double the audit rows.
 */
@Component
public final class AuditEvents {

    private static final String SERVICE = "P2.2";
    private static final String SYSTEM_ACTOR = "system";

    public AuditEvent runStarted(String runId, int candidateCount, boolean dryRun, String actor) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("candidateCount", String.valueOf(candidateCount));
        detail.put("dryRun", String.valueOf(dryRun));
        return event("disposition.run-started", AuditEvent.Outcome.SUCCESS,
                "disposition-run", runId, runId, actor, detail);
    }

    public AuditEvent runCompleted(String runId, int deleted, int skippedHold, int failed, boolean dryRun) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("deleted", String.valueOf(deleted));
        detail.put("skippedHold", String.valueOf(skippedHold));
        detail.put("failed", String.valueOf(failed));
        detail.put("dryRun", String.valueOf(dryRun));
        return event("disposition.run-completed", AuditEvent.Outcome.SUCCESS,
                "disposition-run", runId, runId, SYSTEM_ACTOR, detail);
    }

    public AuditEvent runFailed(String runId, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        return event("disposition.run-failed", AuditEvent.Outcome.FAILURE,
                "disposition-run", runId, runId, SYSTEM_ACTOR, detail);
    }

    public AuditEvent deleted(String runId, ArchiveCandidate candidate, boolean confirmed) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("runId", runId);
        detail.put("externalId", candidate.externalId());
        detail.put("custodianId", candidate.custodianId());
        detail.put("sentAt", candidate.sentAt().toString());
        // The distinction the ledger keeps, kept here too: "gone" and "asked P2 to remove it" are
        // different claims and the audit trail should not blur them.
        detail.put("confirmed", String.valueOf(confirmed));
        return event("disposition.deleted", AuditEvent.Outcome.SUCCESS,
                "message", candidate.messageId(), runId, SYSTEM_ACTOR, detail);
    }

    /**
     * A deletion this service declined to perform. The chain of custody's answer to "why is this
     * message past its retention period and still here?" (FR-4.6, FR-5.3).
     */
    public AuditEvent refused(String runId, ArchiveCandidate candidate, String reason) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("runId", runId);
        detail.put("externalId", candidate.externalId());
        detail.put("custodianId", candidate.custodianId());
        detail.put("reason", reason);
        return event("disposition.refused", AuditEvent.Outcome.REFUSED,
                "message", candidate.messageId(), runId, SYSTEM_ACTOR, detail);
    }

    /**
     * A deletion blocked by a hold on a case, naming the hold and the case.
     *
     * <p>Separate from {@link #refused} because this is the event a regulator actually asks for:
     * not "a message was not deleted" but "the hold on this matter demonstrably prevented this
     * message from being destroyed". {@code caseId} is promoted into the detail so P5's audit
     * viewer can filter a case's chain of custody without parsing a reason string.
     */
    public AuditEvent refusedByCaseHold(String runId, ArchiveCandidate candidate, ActiveHold hold) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("runId", runId);
        detail.put("externalId", candidate.externalId());
        detail.put("custodianId", candidate.custodianId());
        detail.put("holdId", hold.holdId());
        detail.put("caseId", hold.caseId());
        if (hold.caseName() != null) {
            detail.put("caseName", hold.caseName());
        }
        detail.put("scopeApproximate", String.valueOf(hold.isScopeApproximate()));
        detail.put("reason", hold.describe());
        return event("disposition.refused", AuditEvent.Outcome.REFUSED,
                "message", candidate.messageId(), runId, SYSTEM_ACTOR, detail);
    }

    /** A retention period changed (FR-5.1) — it alters what the next sweep destroys, so it is audited. */
    public AuditEvent retentionPolicyChanged(MessageType type, Duration from, Duration to, String actor) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("messageType", type.name());
        detail.put("from", from == null ? "unset" : from.toString());
        detail.put("to", to.toString());
        return event("retention.policy-updated", AuditEvent.Outcome.SUCCESS,
                "retention-policy", type.name(), type.name(), actor, detail);
    }

    private AuditEvent event(String action, AuditEvent.Outcome outcome, String subjectType,
                             String subjectId, String correlationId, String actor,
                             Map<String, String> detail) {
        Instant occurredAt = Instant.now();
        String eventId = UUID.nameUUIDFromBytes(
                (action + "|" + subjectId + "|" + occurredAt).getBytes(StandardCharsets.UTF_8)).toString();
        return new AuditEvent(eventId, occurredAt, SERVICE, action, outcome,
                subjectType, subjectId, actor == null ? SYSTEM_ACTOR : actor, correlationId, detail);
    }
}
