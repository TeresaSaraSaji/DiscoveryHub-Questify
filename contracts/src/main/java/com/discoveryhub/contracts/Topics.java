package com.discoveryhub.contracts;

/**
 * Kafka topic names. Broker-side auto-create is disabled, so a typo here fails loudly at startup
 * rather than quietly creating a topic nothing produces to — but only if everyone references
 * these constants instead of writing the strings out.
 *
 * <p>Adding a topic means adding it here <i>and</i> in {@code infra/kafka/create-topics.sh}.
 */
public final class Topics {

    /** P1 to P2. Accepted, deduped, not yet stored. */
    public static final String MESSAGES_INGESTED = "messages.ingested";

    /** P2 to P3. Written to the archive, ready to index. */
    public static final String MESSAGES_ARCHIVED = "messages.archived";

    /** P4 to its own workers. Fan-out over a hold scope too large to resolve synchronously. */
    public static final String HOLDS_COMMANDS = "holds.commands";

    /** P4 to P3 and P2. A custodian or message came under hold, or came off it. */
    public static final String HOLDS_EVENTS = "holds.events";

    /**
     * P2.2 to P2. One message past retention and covered by no hold, to be removed from the
     * archive. A request, not a warrant: the archive still refuses held messages, because holds
     * may have changed since the sweep decided.
     */
    public static final String DISPOSITION_COMMANDS = "disposition.commands";

    /** P5 to its own workers. One export job to build. */
    public static final String EXPORT_JOBS = "export.jobs";

    /** Everyone to P5. Chain of custody. */
    public static final String AUDIT_EVENTS = "audit.events";

    private Topics() {
    }
}
