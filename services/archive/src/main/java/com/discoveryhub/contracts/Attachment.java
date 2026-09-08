package com.discoveryhub.contracts;

/**
 * A file carried by a message.
 *
 * <p>{@code contentBase64} is only populated on the ingestion path. Once the storage service has
 * written the bytes to local disk (and optionally offloaded a copy to S3) it is dropped from every
 * downstream representation; {@code sha256} is the chain-of-custody anchor from that point on and is
 * what the export verifier re-computes.
 */
public record Attachment(
        String attachmentId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String contentBase64) {
}
