package com.discoveryhub.holds.command;

/**
 * The lightweight wire payload for {@code holds.commands} — just enough for the consumer to load
 * the hold and decide which {@link HoldCommand} to build. The scope and case context live on the
 * persisted hold row, so the message stays small and the command is rehydrated from the database,
 * not from the queue. A restart that re-delivers this message re-reads the hold's current state
 * rather than acting on a stale snapshot.
 */
public record HoldCommandMessage(
        String holdId,
        String type,
        String correlationId) {

    public static final String TYPE_PLACE = "PLACE";
    public static final String TYPE_RELEASE = "RELEASE";
}
