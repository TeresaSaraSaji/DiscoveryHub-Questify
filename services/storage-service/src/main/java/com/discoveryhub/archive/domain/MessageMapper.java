package com.discoveryhub.archive.domain;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Ids;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Translates between the frozen {@link Message} wire format and the persistent entities, and back
 * out to the archived representation that downstream services see.
 *
 * <p>The archived shape is the same as the ingestion shape with one field dropped: attachment
 * {@code contentBase64} is gone, because the bytes now live in {@code attachments.content} and
 * {@code sha256} is what everything downstream reasons about (message-schema.md). This is what P2
 * publishes to {@code messages.archived} and what its read API returns.
 */
@Component
public class MessageMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper json;

    public MessageMapper(ObjectMapper json) {
        this.json = json;
    }

    /** Wire message -> entities ready to persist. Assigns {@code messageId} if the source omitted it. */
    public MessageEntity toEntity(Message m) {
        String messageId = m.messageId() != null ? m.messageId() : Ids.messageId(m.externalId());
        MessageEntity e = new MessageEntity();
        e.setMessageId(messageId);
        e.setExternalId(m.externalId());
        e.setSource(m.source());
        e.setType(m.type());
        e.setCustodianId(m.custodianId());
        e.setFrom(m.from());
        e.setTo(writeList(m.to()));
        e.setCc(writeList(m.cc()));
        e.setSubject(m.subject());
        e.setBody(m.body());
        e.setSentAt(m.sentAt());
        e.setThreadId(m.threadId());
        e.setInReplyTo(m.inReplyTo());
        e.setLabels(writeList(m.labels()));
        e.setOnHold(false);
        e.setHoldCount(0);
        e.setAttachmentCount(m.attachments().size());
        e.setArchivedAt(Instant.now());
        return e;
    }

    /** Wire attachment -> entity, decoding the base64 content and anchoring {@code sha256}. */
    public AttachmentEntity toEntity(Attachment a, String messageId, int ordinal) {
        byte[] bytes = a.contentBase64() != null && !a.contentBase64().isBlank()
                ? Base64.getDecoder().decode(a.contentBase64())
                : new byte[0];
        AttachmentEntity e = new AttachmentEntity();
        e.setAttachmentId(a.attachmentId() != null ? a.attachmentId() : Ids.attachmentId(messageId, ordinal));
        e.setMessageId(messageId);
        e.setOrdinal(ordinal);
        e.setFilename(a.filename());
        e.setContentType(a.contentType());
        e.setSizeBytes(a.sizeBytes() > 0 ? a.sizeBytes() : bytes.length);
        // sha256 is the chain-of-custody anchor. If the source omitted it, derive it from the bytes
        // so the anchor always exists before anything downstream can reference it.
        e.setSha256(a.sha256() != null && !a.sha256().isBlank() ? a.sha256() : sha256Hex(bytes));
        e.setContent(bytes);
        return e;
    }

    /** Entity -> archived wire message. Attachment bytes are not included; only {@code sha256}. */
    public Message toArchived(MessageEntity e, List<AttachmentEntity> attachments) {
        List<Attachment> wireAttachments = new ArrayList<>();
        for (AttachmentEntity a : attachments) {
            wireAttachments.add(new Attachment(
                    a.getAttachmentId(), a.getFilename(), a.getContentType(),
                    a.getSizeBytes(), a.getSha256(), null));
        }
        return new Message(
                e.getMessageId(), e.getExternalId(), e.getSource(), e.getType(),
                e.getCustodianId(), e.getFrom(), readList(e.getTo()), readList(e.getCc()),
                e.getSubject(), e.getBody(), e.getSentAt(), e.getThreadId(), e.getInReplyTo(),
                wireAttachments, readList(e.getLabels()));
    }

    private String writeList(List<String> values) {
        try {
            return json.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialise message list field", ex);
        }
    }

    private List<String> readList(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(text, STRING_LIST);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse message list field: " + text, ex);
        }
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

    @SuppressWarnings("unused")
    private static String utf8(String s) {
        return s == null ? null : new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
