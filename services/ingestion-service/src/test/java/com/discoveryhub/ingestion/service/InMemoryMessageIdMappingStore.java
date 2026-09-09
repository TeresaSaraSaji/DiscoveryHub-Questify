package com.discoveryhub.ingestion.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test double for {@link MessageIdMappingStore}: no Postgres, just a map. */
public class InMemoryMessageIdMappingStore implements MessageIdMappingStore {

    private final Map<String, String> mapped = new ConcurrentHashMap<>();

    @Override
    public boolean claim(String externalId, String messageId) {
        return mapped.putIfAbsent(externalId, messageId) == null;
    }

    @Override
    public void release(String externalId) {
        mapped.remove(externalId);
    }
}
