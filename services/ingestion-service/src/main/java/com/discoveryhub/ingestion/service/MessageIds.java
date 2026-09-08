package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Ids;
import com.discoveryhub.contracts.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Assigns DiscoveryHub ids to an incoming message.
 *
 * <p>A client may omit {@code messageId} and {@code attachmentId}, or send whatever it likes;
 * either way P1 overwrites them from {@code externalId} via {@link Ids}. Ids are ours to assign,
 * not the source system's to choose, and they must be reproducible so that loading the same corpus
 * twice yields the same ids everywhere.
 */
final class MessageIds {

    private MessageIds() {
    }

    static Message withDerivedIds(Message incoming) {
        List<Attachment> attachments = new ArrayList<>(incoming.attachments().size());
        for (int i = 0; i < incoming.attachments().size(); i++) {
            Attachment a = incoming.attachments().get(i);
            attachments.add(new Attachment(
                    Ids.attachmentId(incoming.externalId(), i),
                    a.filename(),
                    a.contentType(),
                    a.sizeBytes(),
                    a.sha256(),
                    a.contentBase64()));
        }
        return new Message(
                Ids.messageId(incoming.externalId()),
                incoming.externalId(),
                incoming.source(),
                incoming.type(),
                incoming.custodianId(),
                incoming.from(),
                incoming.to(),
                incoming.cc(),
                incoming.subject(),
                incoming.body(),
                incoming.sentAt(),
                incoming.threadId(),
                incoming.inReplyTo(),
                attachments,
                incoming.labels());
    }
}
