package com.discoveryhub.export.service;

/**
 * The built package, ready to upload: its bytes, its whole-file checksum, how many items went in,
 * and how many were named in the scope but no longer in the archive.
 *
 * <p>{@code missingCount} is carried out to the job record so the absence is visible without
 * opening the package — an investigator should not have to unzip a manifest to find out that an
 * export is not everything the case names.
 */
public record PackageResult(byte[] zipBytes, String packageSha256, int itemCount, int missingCount) {
}
