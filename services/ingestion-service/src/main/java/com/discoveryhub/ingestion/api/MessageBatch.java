package com.discoveryhub.ingestion.api;

import com.discoveryhub.contracts.Message;

import java.util.List;

/**
 * A request body after each element has been decoded on its own.
 *
 * <p>Binding the body straight to {@code List<Message>} makes a batch atomic in the one place the
 * contract says it must not be. Jackson abandons the whole array on the first value it cannot
 * convert, so a single unknown {@code type} or a null inside {@code to} loses the other 249
 * messages and returns a framework error page instead of per-item outcomes. Decoding element by
 * element turns that into one {@code REJECTED} beside 249 {@code ACCEPTED}s, which is what
 * {@code message-schema.md} promises.
 */
public record MessageBatch(List<Entry> entries) {

    public MessageBatch {
        entries = List.copyOf(entries);
    }

    /**
     * One element of the batch: either a decoded message, or the reason it could not be decoded.
     * {@code externalId} is recovered from the raw JSON even when decoding failed, so a client can
     * tell which message it needs to fix.
     */
    public record Entry(Message message, String externalId, String rejection) {

        public static Entry decoded(Message message) {
            return new Entry(message, message == null ? null : message.externalId(), null);
        }

        public static Entry undecodable(String externalId, String rejection) {
            return new Entry(null, externalId, rejection);
        }
    }

    /** Wraps an already-decoded list, for callers that did not come in over HTTP. */
    public static MessageBatch of(List<Message> messages) {
        return new MessageBatch(messages.stream().map(Entry::decoded).toList());
    }

    public int size() {
        return entries.size();
    }
}
