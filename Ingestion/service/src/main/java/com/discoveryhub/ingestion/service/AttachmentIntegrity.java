package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Checks that an attachment's claimed {@code sha256} and {@code sizeBytes} actually describe its
 * {@code contentBase64}.
 *
 * <p>FR-6.5 anchors the whole chain of custody on {@code sha256}: P5 puts it in the export
 * manifest and the verifier CLI recomputes it from the delivered package. If P1 takes the source
 * system's word for it, a wrong hash is not detected here, is attested to by an audit event that
 * never checked it, and finally surfaces as a failed export verification — the one step of the
 * demo that is supposed to prove the evidence is sound. Verifying at the boundary is cheap and
 * turns a late, confusing failure into an immediate, specific rejection.
 */
final class AttachmentIntegrity {

    private AttachmentIntegrity() {
    }

    /**
     * @return null if every attachment checks out, otherwise a reason naming the offending file
     */
    static String check(Message message) {
        for (int i = 0; i < message.attachments().size(); i++) {
            String reason = check(message.attachments().get(i), i);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static String check(Attachment a, int index) {
        String label = "attachment[" + index + "]";
        if (a.contentBase64() == null) {
            // Metadata-only attachments are not this service's business to reject; P1 only
            // verifies what it can actually see the bytes of.
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(a.contentBase64());
        } catch (IllegalArgumentException e) {
            return label + " contentBase64 is not valid base64";
        }
        if (a.sizeBytes() != bytes.length) {
            return label + " sizeBytes " + a.sizeBytes() + " does not match content length " + bytes.length;
        }
        if (a.sha256() == null || a.sha256().isBlank()) {
            return label + " sha256 is required when content is present";
        }
        String actual = sha256Hex(bytes);
        if (!actual.equalsIgnoreCase(a.sha256())) {
            return label + " sha256 mismatch: claimed " + a.sha256() + ", computed " + actual;
        }
        return null;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
