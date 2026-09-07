package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Random;

/**
 * Builds attachments with real (if synthetic) bytes. Real bytes matter: P5 has to checksum them
 * into an export manifest and the verifier CLI has to re-compute those checksums, so a corpus of
 * empty placeholder files would leave FR-6.5 untestable until demo day.
 */
final class Attachments {

    private Attachments() {
    }

    static Attachment build(String externalId, int index, String filename, Random rng) {
        byte[] content = content(filename, rng);
        return of(externalId, index, filename, content);
    }

    /** Attachment with caller-supplied bytes, used by the planted narrative. */
    static Attachment of(String externalId, int index, String filename, byte[] content) {
        return new Attachment(
                Ids.attachmentId(externalId, index),
                filename,
                contentType(filename),
                content.length,
                sha256(content),
                Base64.getEncoder().encodeToString(content));
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String contentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private static byte[] content(String filename, Random rng) {
        String body = filename.toLowerCase(Locale.ROOT).endsWith(".csv") ? csv(rng) : text(rng);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private static final String[] CSV_HEADERS = {
            "reference,description,quantity,unit_cost,total,status",
            "date,account,cost_centre,amount,currency,notes",
            "id,owner,opened,severity,state,summary",
    };

    private static final String[] CSV_WORDS = {
            "northgate", "bridgeline", "depot-east", "depot-west", "platform", "field-services",
            "tooling", "haulage", "inspection", "spares", "training", "licences",
    };

    private static String csv(Random rng) {
        StringBuilder sb = new StringBuilder(CSV_HEADERS[rng.nextInt(CSV_HEADERS.length)]).append('\n');
        int rows = 8 + rng.nextInt(40);
        for (int i = 0; i < rows; i++) {
            sb.append(String.format(Locale.ROOT, "%s-%04d,%s,%d,%.2f,%.2f,%s%n",
                    CSV_WORDS[rng.nextInt(CSV_WORDS.length)].toUpperCase(Locale.ROOT),
                    rng.nextInt(9999),
                    CSV_WORDS[rng.nextInt(CSV_WORDS.length)],
                    1 + rng.nextInt(250),
                    5 + rng.nextDouble() * 4000,
                    50 + rng.nextDouble() * 90000,
                    rng.nextBoolean() ? "approved" : "pending"));
        }
        return sb.toString();
    }

    private static final String[] TEXT_LINES = {
            "Prepared by the programme office. Circulation restricted to named recipients.",
            "Section 1. Scope. This document covers the work packages agreed at the last review.",
            "Section 2. Assumptions. Lead times are quoted from confirmed order, not from enquiry.",
            "Section 3. Risks. Supplier concentration remains the largest single exposure.",
            "Action: confirm the revised dates with the site team before publication.",
            "Action: obtain written approval from the budget holder for any variance above five percent.",
            "Note: figures in this document are indicative and subject to final reconciliation.",
            "Note: this draft supersedes all previous versions circulated by email.",
            "Appendix A lists the reference documents relied upon in preparing this note.",
            "Sign-off is required from operations, finance, and legal before issue.",
    };

    private static String text(Random rng) {
        StringBuilder sb = new StringBuilder();
        int lines = 6 + rng.nextInt(14);
        for (int i = 0; i < lines; i++) {
            sb.append(TEXT_LINES[rng.nextInt(TEXT_LINES.length)]).append('\n');
        }
        return sb.toString();
    }
}
