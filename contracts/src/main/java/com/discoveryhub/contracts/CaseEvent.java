package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Case lifecycle event published by the case-service to {@link Topics#CASES_EVENTS} and consumed
 * by the hold-service. The hold-service acts on transitions to {@code CLOSED}: a closed case is
 * read-only (FR-2.4) and its holds are released (FR-4.5).
 *
 * <p>{@code action} is the verb, dotted and past tense, matching the {@code AuditEvent} style:
 * {@code case.created}, {@code case.updated}, {@code case.transitioned}, {@code case.closed}.
 * {@code status} is the resulting case status; {@code previousStatus} is present on transitions
 * only. The hold-service does not need every field, but publishing the full lifecycle keeps the
 * topic useful to any future consumer (frontend, audit reconciliation) without a schema change.
 *
 * <p>Additive optional fields are fine; renaming or retyping existing fields is a breaking change
 * for the hold-service consumer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseEvent(
        String caseId,
        String action,
        String status,
        String previousStatus,
        String correlationId,
        Instant occurredAt) {
}
