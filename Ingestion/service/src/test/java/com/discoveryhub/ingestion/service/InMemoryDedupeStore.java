package com.discoveryhub.ingestion.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryDedupeStore implements DedupeStore {

    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    @Override
    public boolean claim(String namespace, String key) {
        return seen.add(namespace + ":" + key);
    }

    @Override
    public void release(String namespace, String key) {
        seen.remove(namespace + ":" + key);
    }

    boolean hasClaim(String namespace, String key) {
        return seen.contains(namespace + ":" + key);
    }
}
