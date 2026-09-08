package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The frozen message wire format. This is the body of {@code POST /messages} on P1 Ingestion, the
 * payload of {@code messages.ingested} and {@code messages.archived}, and the shape P2 stores.
 *
 * <p>Two identifiers, and they are not interchangeable:
 * <ul>
 *   <li>{@code externalId} is assigned by the source system and is the <b>idempotency key</b>.
 *       P1 dedupes on it in Redis and P2 carries a unique constraint on it (FR-1.6).</li>
 *   <li>{@code messageId} is DiscoveryHub's own identifier and is what every other service
 *       references. It is derived deterministically from {@code externalId} so that replaying
 *       a corpus produces stable ids across environments.</li>
 * </ul>
 *
 * <p>{@code custodianId} is the owner of the mailbox this copy came from, not the sender. The same
 * conversation captured from two mailboxes yields two messages with different {@code externalId}s.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        String messageId,
        String externalId,
        String source,
        MessageType type,
        String custodianId,
        String from,
        List<String> to,
        List<String> cc,
        String subject,
        String body,
        Instant sentAt,
        String threadId,
        String inReplyTo,
        List<Attachment> attachments,
        List<String> labels) {

    public Message {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        labels = labels == null ? List.of() : List.copyOf(labels);
    }
}
