package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in the chain of custody (FR-7). Every service emits its own; P5 only appends.
 *
 * <p><b>STRAWMAN — not yet ratified.</b> Argue with this before Day 1 coding hardens it into five
 * services. Once P1 through P5 are all emitting, changing the shape means changing all of them.
 *
 * <p>Design intent worth preserving whatever the field names end up being:
 * <ul>
 *   <li><b>The emitter decides what happened.</b> P5 records, it does not infer. A service that
 *       deduped a message is the only thing that knows it deduped a message.</li>
 *   <li><b>Failures are audited too.</b> A disposition job that refused to delete because a hold
 *       was in force is the single most important event in the whole system, and it is not a
 *       success. {@code outcome} exists so refusals are recorded rather than logged and lost.</li>
 *   <li><b>{@code detail} is deliberately loose.</b> Freezing a typed payload per action across
 *       five services in four days will not converge. Anything the UI must filter or sort on gets
 *       promoted to a real field; everything else stays in the map.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
        /* Deterministic where possible, so a Kafka replay does not duplicate audit rows. */
        String eventId,
        Instant occurredAt,
        /* Emitting service: "P1" .. "P5". */
        String service,
        /* Verb, past tense, dotted: message.ingested, message.deduped, hold.placed,
         * disposition.refused, export.completed. */
        String action,
        Outcome outcome,
        /* What the action was performed on. */
        String subjectType,
        String subjectId,
        /* Single persona for now, so this is a placeholder rather than a real identity.
         * Present from day one because retrofitting an actor onto an append-only table is
         * unpleasant. */
        String actor,
        /* Correlates every event produced by one user action across all five services. */
        String correlationId,
        Map<String, String> detail) {

    public enum Outcome {
        SUCCESS,
        /** The system deliberately declined. A hold blocking a delete lands here. */
        REFUSED,
        FAILURE
    }

    public AuditEvent {
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
