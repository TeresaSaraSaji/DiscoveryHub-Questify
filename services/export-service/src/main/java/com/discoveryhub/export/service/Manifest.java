package com.discoveryhub.export.service;

import java.time.Instant;
import java.util.List;

/**
 * The manifest written into every export package (FR-6.3). Deliberately does not carry the
 * package-level checksum: that checksum covers the whole zip file, including this manifest, so it
 * cannot also be a field inside it without a chicken-and-egg problem. The package checksum is
 * reported by the job status API and by a {@code <jobId>.sha256} sidecar object next to the
 * package in the packages bucket instead.
 */
public record Manifest(String jobId, String caseId, Instant generatedAt, List<ManifestItem> items) {
}
