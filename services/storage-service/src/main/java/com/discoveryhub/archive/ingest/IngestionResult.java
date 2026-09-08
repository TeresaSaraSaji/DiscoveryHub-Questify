package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.MessageEntity;

import java.util.List;

/**
 * What {@link ArchiveService#ingest} did with one message. For a {@code STORED} outcome the saved
 * entities are returned so the listener can build the archived wire message without re-reading the
 * database; for a {@code DEDUPED} outcome only the {@code externalId} is meaningful.
 */
public record IngestionResult(IngestionOutcome outcome, String externalId,
                              MessageEntity entity, List<AttachmentEntity> attachments) {

    static IngestionResult stored(MessageEntity entity, List<AttachmentEntity> attachments) {
        return new IngestionResult(IngestionOutcome.STORED, entity.getExternalId(), entity, attachments);
    }

    static IngestionResult deduped(String externalId) {
        return new IngestionResult(IngestionOutcome.DEDUPED, externalId, null, List.of());
    }
}
