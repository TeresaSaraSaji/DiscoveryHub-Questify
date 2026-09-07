package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param contentBase64 ingestion path only. P2 drops it once the bytes are in object storage; it
 *                      must never appear in P2's read API, in search results or in audit events.
 *                      Downstream, {@code sha256} is what everything reasons about.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Attachment(
        String attachmentId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String contentBase64) {

    public Attachment withoutContent() {
        return new Attachment(attachmentId, filename, contentType, sizeBytes, sha256, null);
    }
}
