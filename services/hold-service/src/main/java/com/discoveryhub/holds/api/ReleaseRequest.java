package com.discoveryhub.holds.api;

/** Optional reason for a manual release, carried in the audit trail. */
public record ReleaseRequest(String reason) {
}
