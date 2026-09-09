package com.discoveryhub.contracts;

/**
 * Reserved values in {@link Message#labels()} that P2 reads as retention instructions rather than
 * pure classification.
 *
 * <p>This piggybacks on {@code labels} instead of adding a field to {@link Message} on purpose:
 * the wire format is frozen and used by all five services, and {@code labels} is already the
 * documented escape hatch for exactly this kind of thing — {@link ContentHash} deliberately
 * excludes it because "classification is mutable; re-sending a message with a label added is
 * still the same message," which is precisely the property a retention override needs (tagging a
 * message for the demo must not change its identity or its dedupe key).
 */
public final class RetentionLabels {

    private RetentionLabels() {
    }

    /**
     * P1's upload UI attaches this when the uploader picks the "demonstration" retention option
     * instead of the normal, type-based one. P2's {@code ArchiveService} reads it at ingestion
     * time and records a short, absolute eligibility timestamp on the message instead of relying
     * on {@code discoveryhub.retention.per-type}, so one demoed document becomes eligible for
     * disposition in minutes without changing the retention period for every other message of its
     * type.
     */
    public static final String DEMO_RETENTION = "retention:demo";
}
