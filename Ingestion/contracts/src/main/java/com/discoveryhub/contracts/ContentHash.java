package com.discoveryhub.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A stable fingerprint of a captured message, used as a second idempotency key alongside
 * {@code externalId}.
 *
 * <p>{@code externalId} only catches a source system re-sending the <i>same record</i>. It cannot
 * catch the same message arriving twice under two different source keys — a re-export, a
 * re-crawl, or two connectors pointed at one mailbox. This closes that gap.
 *
 * <p><b>{@code custodianId} is part of the fingerprint, and that is the whole design.</b> The same
 * conversation captured from two mailboxes is not a duplicate: it is two pieces of evidence, and a
 * hold on one custodian must preserve their copy independently of the other's. Hashing content
 * alone would collapse them and silently destroy evidence. Including the custodian means:
 *
 * <ul>
 *   <li>same content, same mailbox, different {@code externalId} → duplicate, dropped</li>
 *   <li>same content, different mailbox → two records, both kept</li>
 * </ul>
 *
 * <p>Fields are length-prefixed before hashing so no combination of values can be rearranged into
 * another message's fingerprint — without it, a subject ending in a delimiter could impersonate a
 * body beginning with one.
 *
 * <p>Excluded on purpose: {@code externalId} and {@code messageId} (identifiers, not content),
 * {@code attachmentId} (derived from {@code externalId}), and {@code labels} (classification is
 * mutable; re-sending a message with a label added is still the same message).
 *
 * <p>P1 and P2 must both call this. If they compute fingerprints differently, the two layers of
 * the guarantee disagree and the disagreement will not surface until something is already lost.
 */
public final class ContentHash {

    private ContentHash() {
    }

    public static String of(Message m) {
        StringBuilder canonical = new StringBuilder(512);
        field(canonical, m.custodianId());
        field(canonical, m.source());
        field(canonical, m.type() == null ? null : m.type().name());
        field(canonical, m.from());
        list(canonical, m.to());
        list(canonical, m.cc());
        field(canonical, m.subject());
        field(canonical, m.body());
        field(canonical, m.sentAt() == null ? null : m.sentAt().toString());
        field(canonical, m.threadId());
        field(canonical, m.inReplyTo());
        for (Attachment a : m.attachments()) {
            field(canonical, a.filename());
            field(canonical, a.sha256());
            field(canonical, Long.toString(a.sizeBytes()));
        }
        return sha256(canonical.toString());
    }

    /** Length-prefixed so field boundaries cannot be forged by content. Null is distinct from "". */
    private static void field(StringBuilder sb, String value) {
        if (value == null) {
            sb.append("-1:");
            return;
        }
        sb.append(value.length()).append(':').append(value);
    }

    private static void list(StringBuilder sb, List<String> values) {
        sb.append(values.size()).append('[');
        values.forEach(v -> field(sb, v));
        sb.append(']');
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
