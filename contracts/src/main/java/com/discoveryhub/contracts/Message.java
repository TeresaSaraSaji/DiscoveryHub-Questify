package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * The frozen message contract. See docs/message-schema.md.
 *
 * <p>Absent and empty are distinguished on the wire: optional fields ({@code subject},
 * {@code inReplyTo}) are omitted rather than sent as null, while array fields are always present
 * and may be empty.
 *
 * @param externalId the source system's key, and the only thing dedupe operates on
 * @param messageId  derived from externalId via {@link Ids#messageId(String)}, never random
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

    public boolean hasAttachments() {
        return !attachments.isEmpty();
    }

    /**
     * Returns this message with {@code messageId} and every {@code attachmentId} derived from
     * {@code externalId}. A client may omit them; ids are ours to assign, not theirs to choose.
     */
    public Message withDerivedIds() {
        List<Attachment> derived = new java.util.ArrayList<>(attachments.size());
        for (int i = 0; i < attachments.size(); i++) {
            Attachment a = attachments.get(i);
            derived.add(new Attachment(
                    Ids.attachmentId(externalId, i).toString(),
                    a.filename(),
                    a.contentType(),
                    a.sizeBytes(),
                    a.sha256(),
                    a.contentBase64()));
        }
        return new Message(
                Ids.messageId(externalId).toString(),
                externalId, source, type, custodianId, from, to, cc, subject, body,
                sentAt, threadId, inReplyTo, derived, labels);
    }
}
