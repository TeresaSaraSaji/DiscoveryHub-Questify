package com.discoveryhub.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A file carried by a message.
 *
 * <p>{@code contentBase64} is only populated on the ingestion path. Once P2 has written the bytes
 * to object storage it is dropped from every downstream representation; {@code sha256} is the
 * chain-of-custody anchor from that point on and is what the export verifier re-computes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Attachment(
        String attachmentId,
        String filename,
        String contentType,
        long sizeBytes,
        String sha256,
        String contentBase64) {
}
