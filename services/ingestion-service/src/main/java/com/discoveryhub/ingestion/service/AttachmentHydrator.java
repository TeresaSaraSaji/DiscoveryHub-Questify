package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Fills in an attachment's {@code sha256} and {@code sizeBytes} when the uploader did not supply
 * them.
 *
 * <p>This exists because the two ingestion paths have genuinely different trust models, and the
 * difference is easy to mistake for an inconsistency:
 *
 * <ul>
 *   <li><b>{@code POST /messages}</b> is a source system talking to us. It knows the checksum of
 *       what it holds, so it must attest to it, and {@link AttachmentIntegrity} rejects a mismatch
 *       at the boundary. A wrong hash accepted here would be attested to by an audit event that
 *       never checked it and would finally surface as a failed export verification — the one step
 *       of the demo meant to prove the evidence is sound.</li>
 *   <li><b>{@code POST /messages/upload}</b> is a person dragging in a file. There is no upstream
 *       system to attest to anything; we <i>are</i> the origin. Demanding a checksum the uploader
 *       cannot possibly know would reject every real email that has an attachment.</li>
 * </ul>
 *
 * <p>So: absent is computed, present is still verified. An uploader that does supply a checksum is
 * held to it exactly as an API caller would be — hydration never overwrites a claim, it only fills
 * a gap. That way adding the upload path cannot be used to smuggle past the integrity check.
 */
@Component
public class AttachmentHydrator {

    public Message hydrate(Message message) {
        if (message == null || message.attachments().isEmpty() || !needsHydration(message)) {
            return message;
        }
        List<Attachment> hydrated = new ArrayList<>(message.attachments().size());
        for (Attachment a : message.attachments()) {
            hydrated.add(hydrate(a));
        }
        return new Message(
                message.messageId(), message.externalId(), message.source(), message.type(),
                message.custodianId(), message.from(), message.to(), message.cc(),
                message.subject(), message.body(), message.sentAt(), message.threadId(),
                message.inReplyTo(), hydrated, message.labels());
    }

    private static boolean needsHydration(Message message) {
        return message.attachments().stream().anyMatch(a -> a.contentBase64() != null
                && (a.sha256() == null || a.sha256().isBlank() || a.sizeBytes() <= 0));
    }

    private Attachment hydrate(Attachment a) {
        if (a.contentBase64() == null) {
            return a;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(a.contentBase64());
        } catch (IllegalArgumentException e) {
            // Leave it alone. AttachmentIntegrity reports invalid base64 with a better message
            // than anything this class could raise, and rejection is its job, not ours.
            return a;
        }
        boolean claimsHash = a.sha256() != null && !a.sha256().isBlank();
        return new Attachment(
                a.attachmentId(),
                a.filename(),
                a.contentType(),
                a.sizeBytes() > 0 ? a.sizeBytes() : bytes.length,
                claimsHash ? a.sha256() : sha256Hex(bytes),
                a.contentBase64());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
