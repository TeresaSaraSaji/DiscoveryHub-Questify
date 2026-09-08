package com.discoveryhub.tools.corpus;

import com.discoveryhub.contracts.Custodian;
import com.discoveryhub.contracts.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * A generated corpus. {@code unique} holds one entry per {@code externalId}; {@code duplicates}
 * holds deliberate re-sends of ids already present in {@code unique}.
 */
record Corpus(List<Custodian> custodians, List<Message> unique, List<Message> duplicates) {

    /** Everything in the order it should be ingested: unique traffic first, then the re-sends. */
    List<Message> wire() {
        List<Message> all = new ArrayList<>(unique);
        all.addAll(duplicates);
        return all;
    }
}
