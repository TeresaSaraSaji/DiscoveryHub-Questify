package com.discoveryhub.archive.domain;

/**
 * One attachment, embedded in {@link ArchivedMessageDocument}. Unlike the previous local-disk /
 * S3 layout, the bytes ({@code contentBase64}) live directly on the message document in Mongo —
 * there is no separate blob store and no storage pointer to keep in sync.
 *
 * <p>{@code sha256} remains the chain-of-custody anchor that the export verifier re-computes
 * (FR-6.5).
 */
public record AttachmentDocument(
        String attachmentId,
        int ordinal,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String contentBase64) {
}
