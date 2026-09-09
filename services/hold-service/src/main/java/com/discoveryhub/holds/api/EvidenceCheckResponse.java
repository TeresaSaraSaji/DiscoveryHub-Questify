package com.discoveryhub.holds.api;

/**
 * One protected item in {@code POST /holds/evidence-check}'s response — only the messages that
 * *are* evidence in a currently-held case appear; an empty array is the normal answer. Mirrors
 * {@code com.discoveryhub.disposition.hold.EvidenceHold}.
 */
public record EvidenceCheckResponse(String messageId, String holdId, String caseId, String caseName) {
}
