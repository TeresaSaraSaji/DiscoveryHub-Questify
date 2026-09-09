package com.discoveryhub.export.service;

import java.util.List;

/**
 * The result of re-verifying a completed export package against its own manifest (FR-6.5). {@code
 * valid} is true only if the whole-package checksum matches and every manifest item's checksum
 * matches the bytes actually found in the package — anything else means the package was tampered
 * with, truncated, or corrupted after it was built.
 */
public record VerificationResult(boolean valid, boolean packageChecksumMatches,
                                 List<String> itemMismatches, int itemsChecked) {
}
