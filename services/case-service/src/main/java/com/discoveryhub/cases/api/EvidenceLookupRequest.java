package com.discoveryhub.cases.api;

import java.util.List;

/** Body of {@code POST /cases/evidence/lookup} — P4's held-case evidence guard for disposition. */
public record EvidenceLookupRequest(List<String> messageIds) {
}
