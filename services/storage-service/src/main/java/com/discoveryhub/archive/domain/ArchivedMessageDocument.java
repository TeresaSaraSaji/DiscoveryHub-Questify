package com.discoveryhub.archive.domain;

import com.discoveryhub.contracts.MessageType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Persistent form of an archived message — the whole message, attachments included, as one
 * document keyed by {@code messageId} (the id P1's {@code message_id_map} table established).
 *
 * <p>This is the system of record for message <i>content</i>. Legal-hold state
 * ({@code on_hold} / {@code hold_count}) is deliberately not kept here: it lives on
 * {@link MessageHoldStatus} in P2's own Postgres, which is also what the retention/disposition
 * sweep queries. Keeping hold state out of this document means a hold update never has to touch
 * (or race with) the message content.
 */
@Document(collection = "messages")
public record ArchivedMessageDocument(
        @Id String messageId,
        String externalId,
        String source,
        MessageType type,
        @Indexed String custodianId,
        String from,
        List<String> to,
        List<String> cc,
        String subject,
        String body,
        Instant sentAt,
        String threadId,
        String inReplyTo,
        List<String> labels,
        List<AttachmentDocument> attachments,
        Instant archivedAt) {
}
