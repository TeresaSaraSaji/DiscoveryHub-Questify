package com.discoveryhub.archive.domain;

import com.discoveryhub.archive.config.RetentionProperties;
import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Ids;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.RetentionLabels;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Translates between the frozen {@link Message} wire format and the two persistent shapes P2
 * keeps for it: {@link ArchivedMessageDocument} (the whole message, attachment bytes included, in
 * Mongo) and {@link MessageHoldStatus} (the slim hold/retention bookkeeping row in Postgres).
 */
@Component
public class MessageMapper {

    private final RetentionProperties retention;

    public MessageMapper(RetentionProperties retention) {
        this.retention = retention;
    }

    /** Wire message -> the Mongo document. Assigns {@code messageId} if the source omitted it. */
    public ArchivedMessageDocument toDocument(Message m) {
        String messageId = m.messageId() != null ? m.messageId() : Ids.messageId(m.externalId());
        List<AttachmentDocument> attachments = new ArrayList<>(m.attachments().size());
        for (int ordinal = 0; ordinal < m.attachments().size(); ordinal++) {
            attachments.add(toDocument(m.attachments().get(ordinal), messageId, ordinal));
        }
        return new ArchivedMessageDocument(
                messageId, m.externalId(), m.source(), m.type(), m.custodianId(),
                m.from(), m.to(), m.cc(), m.subject(), m.body(), m.sentAt(),
                m.threadId(), m.inReplyTo(), m.labels(), attachments, Instant.now());
    }

    /** Wire attachment -> embedded document. The base64 content is kept: it lives in Mongo now. */
    private AttachmentDocument toDocument(Attachment a, String messageId, int ordinal) {
        byte[] bytes = a.contentBase64() != null && !a.contentBase64().isBlank()
                ? Base64.getDecoder().decode(a.contentBase64())
                : new byte[0];
        String attachmentId = a.attachmentId() != null ? a.attachmentId() : Ids.attachmentId(messageId, ordinal);
        long sizeBytes = a.sizeBytes() > 0 ? a.sizeBytes() : bytes.length;
        // sha256 is the chain-of-custody anchor. If the source omitted it, derive it from the
        // bytes so the anchor always exists before anything downstream can reference it.
        String sha256 = a.sha256() != null && !a.sha256().isBlank() ? a.sha256() : sha256Hex(bytes);
        return new AttachmentDocument(attachmentId, ordinal, a.filename(), a.contentType(),
                sizeBytes, sha256, a.contentBase64());
    }

    /**
     * Wire message -> the slim Postgres hold/retention row. A message carrying
     * {@code RetentionLabels.DEMO_RETENTION} gets a short, absolute
     * {@code retentionOverrideAt} instead of the normal type-based cutoff — see
     * {@link RetentionProperties#demoPeriod()}.
     */
    public MessageHoldStatus toHoldStatus(Message m) {
        String messageId = m.messageId() != null ? m.messageId() : Ids.messageId(m.externalId());
        Instant now = Instant.now();
        Instant retentionOverrideAt = m.labels().contains(RetentionLabels.DEMO_RETENTION)
                ? now.plus(retention.demoPeriod())
                : null;
        return new MessageHoldStatus(messageId, m.externalId(), m.custodianId(), m.type(),
                m.sentAt(), now, retentionOverrideAt);
    }

    /** Mongo document -> archived wire message. Attachment bytes are not included; only {@code sha256}. */
    public Message toArchived(ArchivedMessageDocument doc) {
        List<Attachment> wireAttachments = new ArrayList<>();
        for (AttachmentDocument a : doc.attachments()) {
            wireAttachments.add(new Attachment(
                    a.attachmentId(), a.filename(), a.contentType(), a.sizeBytes(), a.sha256(), null));
        }
        return new Message(
                doc.messageId(), doc.externalId(), doc.source(), doc.type(),
                doc.custodianId(), doc.from(), doc.to(), doc.cc(),
                doc.subject(), doc.body(), doc.sentAt(), doc.threadId(), doc.inReplyTo(),
                wireAttachments, doc.labels());
    }

    static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes == null ? new byte[0] : bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Used by disposition to recompute an attachment checksum for verification. */
    public static String checksum(byte[] bytes) {
        return sha256Hex(bytes);
    }
}
