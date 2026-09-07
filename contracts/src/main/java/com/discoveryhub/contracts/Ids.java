package com.discoveryhub.contracts;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic identifier derivation.
 *
 * <p>Every id in DiscoveryHub is a pure function of the source system's key. Loading the same
 * corpus on two machines must produce identical ids, otherwise fixtures, demo scripts and export
 * manifests only work on whichever machine loaded the data first. Nothing here may use
 * {@link UUID#randomUUID()}.
 */
public final class Ids {

    private Ids() {
    }

    public static UUID messageId(String externalId) {
        return name("message:" + require(externalId, "externalId"));
    }

    public static UUID attachmentId(String externalId, int index) {
        return name("attachment:" + require(externalId, "externalId") + ":" + index);
    }

    public static UUID threadId(String threadKey) {
        return name("thread:" + require(threadKey, "threadKey"));
    }

    private static UUID name(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
