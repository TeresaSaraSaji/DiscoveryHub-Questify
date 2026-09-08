package com.discoveryhub.export.service;

/** The built package, ready to upload: its bytes, its whole-file checksum, and its item count. */
public record PackageResult(byte[] zipBytes, String packageSha256, int itemCount) {
}
