package com.discoveryhub.holds.api;

import java.util.List;

/** Body of {@code POST /holds/evidence-check} — "of these messages, which are protected?" */
public record EvidenceCheckRequest(List<String> messageIds) {
}
