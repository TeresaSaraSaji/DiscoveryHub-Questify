package com.discoveryhub.archive.ingest;

/** Result of attempting to archive one ingested message. */
public enum IngestionOutcome {
    /** Stored for the first time; {@code messages.archived} was warranted. */
    STORED,
    /** A message with the same {@code externalId} was already present; dropped per FR-1.6. */
    DEDUPED
}
