package com.discoveryhub.export.service;

import java.time.Instant;
import java.util.List;

/**
 * The manifest written into every export package (FR-6.3). Deliberately does not carry the
 * package-level checksum: that checksum covers the whole zip file, including this manifest, so it
 * cannot also be a field inside it without a chicken-and-egg problem. The package checksum is
 * reported by the job status API and by {@code GET /exports/{jobId}/verify}.
 *
 * <p>{@code missing} is part of the record, not an error. A case can name evidence that retention
 * has since destroyed — legitimately, with its own {@code disposition.deleted} entry in the audit
 * trail — and a package that quietly left those out would claim to be the case's evidence while
 * being something less. Naming them is what makes the gap auditable: a reader can take a
 * {@code messageId} from here to the trail and find out what happened to it.
 */
public record Manifest(String jobId, String caseId, Instant generatedAt, List<ManifestItem> items,
                       List<MissingItem> missing) {

    public Manifest {
        items = items == null ? List.of() : List.copyOf(items);
        missing = missing == null ? List.of() : List.copyOf(missing);
    }
}
