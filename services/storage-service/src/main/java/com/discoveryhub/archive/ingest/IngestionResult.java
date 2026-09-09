package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.domain.ArchivedMessageDocument;
import com.discoveryhub.archive.domain.MessageHoldStatus;

/**
 * What {@link ArchiveService#ingest} did with one message. For a {@code STORED} outcome the saved
 * document and hold-status row are returned so the listener can build the archived wire message
 * without re-reading either store; for a {@code DEDUPED} outcome only the {@code externalId} is
 * meaningful.
 */
public record IngestionResult(IngestionOutcome outcome, String externalId,
                              ArchivedMessageDocument document, MessageHoldStatus holdStatus) {

    static IngestionResult stored(ArchivedMessageDocument document, MessageHoldStatus holdStatus) {
        return new IngestionResult(IngestionOutcome.STORED, document.externalId(), document, holdStatus);
    }

    static IngestionResult deduped(String externalId) {
        return new IngestionResult(IngestionOutcome.DEDUPED, externalId, null, null);
    }
}
