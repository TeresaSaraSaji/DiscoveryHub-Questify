package com.discoveryhub.contracts;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic identifier derivation. Every service that needs to turn a source-system key into a
 * DiscoveryHub id must go through here, otherwise a replay produces a different id and the dedupe
 * guarantee in FR-1.6 leaks.
 */
public final class Ids {

    private Ids() {
    }

    /** Stable DiscoveryHub message id for a source-system {@code externalId}. */
    public static String messageId(String externalId) {
        return derive("message", externalId);
    }

    /** Stable thread id for a source-system conversation key. */
    public static String threadId(String externalThreadKey) {
        return derive("thread", externalThreadKey);
    }

    /** Stable attachment id for an attachment within a message. */
    public static String attachmentId(String externalId, int index) {
        return derive("attachment", externalId + "#" + index);
    }

    private static String derive(String namespace, String key) {
        byte[] bytes = (namespace + ":" + key).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(bytes).toString();
    }
}
