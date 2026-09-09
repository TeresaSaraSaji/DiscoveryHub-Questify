package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * P4 Hold-service notification that a message came under hold, or came off it. Published to the
 * {@link Topics#HOLDS_EVENTS} topic and consumed by P2 (storage) and P3 (search).
 *
 * <p>Either {@code messageId} or {@code custodianId} scopes the event: a message id targets one
 * row, a custodian id targets every row in that mailbox. P2 increments a per-message
 * {@code holdCount} on {@code held=true} and decrements it on {@code held=false}, so overlapping
 * holds (FR-4.5) are handled by counting, not by boolean overwrite.
 *
 * <p><b>Ratified here as a frozen contract.</b> P2 carries a local mirror
 * ({@code com.discoveryhub.archive.messaging.HoldEvent}) from before this record existed; the JSON
 * shape is identical, so P2's local deserialisation keeps working unchanged. The two records must
 * not drift: any field change here is a breaking change for P2 and P3.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HoldEvent(
        /* Null when the event is custodian-scoped. */
        String messageId,
        /* Null when the event is message-scoped. */
        String custodianId,
        /* true = placed / covered; false = released / no longer covered by this hold. */
        boolean held,
        /* The case the hold belongs to. Lets P2/P3 correlate and lets the UI group by case. */
        String caseId,
        String correlationId,
        Instant occurredAt) {
}
