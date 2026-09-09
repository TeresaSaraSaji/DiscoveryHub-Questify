package com.discoveryhub.ingestion.api;

/**
 * Chosen at upload time (checkpoint demo): whether the file's messages carry the normal,
 * type-based retention period or a short, per-message override so disposition can be shown
 * acting on them within the length of a demo.
 *
 * <p>The override never changes retention for anything else of the same type — see
 * {@code com.discoveryhub.contracts.RetentionLabels}.
 */
public enum RetentionMode {
    /** No label added. P2 applies the normal, type-based retention period. */
    NORMAL,
    /** Tags every message in the upload with {@code RetentionLabels.DEMO_RETENTION}. */
    DEMO
}
