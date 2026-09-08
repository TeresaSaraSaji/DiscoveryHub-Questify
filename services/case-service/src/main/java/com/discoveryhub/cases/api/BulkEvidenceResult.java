package com.discoveryhub.cases.api;

/** Summary of a bulk-evidence add, so the caller knows how many were new vs already present. */
public record BulkEvidenceResult(int requested, int added, int alreadyPresent) {
}
