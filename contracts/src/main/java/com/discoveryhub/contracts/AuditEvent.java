package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * One entry in the chain of custody (FR-7). Emitted by every service onto
 * {@link Topics#AUDIT_EVENTS} and consumed only by P5, which appends and never updates.
 *
 * <p>Services emit their own audit events rather than letting P5 infer them, so the record
 * reflects what actually happened rather than what an observer guessed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
        String eventId,
        Instant occurredAt,
        String actor,
        String action,
        String targetType,
        String targetId,
        Outcome outcome,
        Map<String, Object> details) {

    public enum Outcome {
        SUCCESS,
        DUPLICATE,
        REJECTED,
        FAILED
    }

    public AuditEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
