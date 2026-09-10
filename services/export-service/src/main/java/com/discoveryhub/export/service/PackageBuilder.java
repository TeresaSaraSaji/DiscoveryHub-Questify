package com.discoveryhub.export.service;

import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Assembles a checksum-verifiable export package (FR-6.3, FR-6.5): every archived message as
 * readable JSON, every attachment's real bytes, and a manifest tying it all together with a
 * per-item checksum recomputed from what was actually written, not copied from anyone's claim.
 */
@Component
public class PackageBuilder {

    private final ArchiveClient archive;
    private final ObjectMapper json;

    public PackageBuilder(ArchiveClient archive, ObjectMapper json) {
        this.archive = archive;
        this.json = json;
    }

    public PackageResult build(String jobId, String caseId, List<String> messageIds) throws IOException {
        List<ManifestItem> items = new ArrayList<>();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (String messageId : messageIds) {
                Message message = archive.findMessage(messageId)
                        .orElseThrow(() -> new IllegalStateException(
                                "message " + messageId + " is in scope but no longer in the archive"));
                writeMessage(zip, items, message);
                for (Attachment attachment : message.attachments()) {
                    writeAttachment(zip, items, message.messageId(), attachment);
                }
            }
            writeManifest(zip, jobId, caseId, items);
        }

        byte[] zipBytes = buffer.toByteArray();
        return new PackageResult(zipBytes, sha256Hex(zipBytes), items.size());
    }

    private void writeMessage(ZipOutputStream zip, List<ManifestItem> items, Message message) throws IOException {
        byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(message);
        String path = "messages/" + sanitiseId(message.messageId()) + ".json";
        putEntry(zip, path, bytes);
        items.add(ManifestItem.message(message.messageId(), path, sha256Hex(bytes), bytes.length));
    }

    private void writeAttachment(ZipOutputStream zip, List<ManifestItem> items, String messageId,
                                 Attachment attachment) throws IOException {
        byte[] bytes = archive.fetchAttachmentBytes(messageId, attachment.attachmentId());
        String actualSha256 = sha256Hex(bytes);
        if (!actualSha256.equalsIgnoreCase(attachment.sha256())) {
            // The chain of custody breaks here if it breaks anywhere — the bytes P2 handed back do
            // not match the anchor it stored alongside them. Refuse the whole export rather than
            // package evidence that would fail its own verification.
            throw new IllegalStateException("attachment " + attachment.attachmentId()
                    + " sha256 mismatch: archive claims " + attachment.sha256()
                    + ", fetched bytes hash to " + actualSha256);
        }
        String path = "attachments/" + sanitiseId(attachment.attachmentId()) + "_" + sanitise(attachment.filename());
        putEntry(zip, path, bytes);
        items.add(ManifestItem.attachment(messageId, attachment.attachmentId(), path, actualSha256, bytes.length));
    }

    private void writeManifest(ZipOutputStream zip, String jobId, String caseId, List<ManifestItem> items)
            throws IOException {
        Manifest manifest = new Manifest(jobId, caseId, Instant.now(), List.copyOf(items));
        putEntry(zip, "manifest.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    }

    private static void putEntry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Filenames are untrusted input (message-schema.md) — strip anything that is not a safe zip path segment. */
    private static String sanitise(String filename) {
        String base = filename == null ? "attachment" : filename;
        String stripped = base.replaceAll("[\\\\/\u0000]", "_").strip();
        return stripped.isEmpty() ? "attachment" : stripped;
    }

    /**
     * messageId/attachmentId are as untrusted as filenames (message-schema.md) — both are used
     * directly in a zip entry path, and unlike {@code filename} they were not sanitised at all
     * before this. An id containing {@code ../} would be a zip-slip entry (e.g.
     * {@code attachments/../manifest.json}, overwriting the manifest itself on extraction). In
     * normal operation these ids are deterministic UUIDs, so the guard should never actually fire
     * — but the zip path must not depend on that being true.
     */
    private static String sanitiseId(String id) {
        String base = id == null ? "unknown" : id;
        String stripped = base.replaceAll("[\\\\/\u0000]", "_").strip();
        return stripped.isEmpty() || stripped.equals(".") || stripped.equals("..") ? "unknown" : stripped;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes == null ? new byte[0] : bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
