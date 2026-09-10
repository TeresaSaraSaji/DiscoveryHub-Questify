package com.discoveryhub.export.service;

/**
 * One line of {@code manifest.json} inside an export package (FR-6.3). {@code sha256} is always
 * recomputed from the bytes actually written into the package — never trusted from P2's metadata
 * — so the manifest is the anchor the verifier CLI re-checks against, not a copy of someone else's
 * claim (message-schema.md's "recompute, don't trust" pattern, applied one more hop downstream).
 */
public record ManifestItem(String type, String messageId, String attachmentId, String path,
                           String sha256, long sizeBytes) {

    static ManifestItem message(String messageId, String path, String sha256, long sizeBytes) {
        return new ManifestItem("message", messageId, null, path, sha256, sizeBytes);
    }

    static ManifestItem attachment(String messageId, String attachmentId, String path,
                                   String sha256, long sizeBytes) {
        return new ManifestItem("attachment", messageId, attachmentId, path, sha256, sizeBytes);
    }
}
