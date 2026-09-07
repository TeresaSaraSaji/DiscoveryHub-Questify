package com.discoveryhub.ingestion.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryDedupeStore implements DedupeStore {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean claim(String externalId) {
        return seen.add(externalId);
    }

    @Override
    public void release(String externalId) {
        seen.remove(externalId);
    }

    boolean hasClaim(String externalId) {
        return seen.contains(externalId);
    }
}
