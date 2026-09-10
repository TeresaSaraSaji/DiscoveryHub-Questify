package com.discoveryhub.export.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Re-verifies a completed package entirely from its own bytes — no call back to P2, no trust in
 * anything the job record claims. This is deliberately the same computation the standalone
 * verifier CLI mentioned in the corpus docs would perform on a delivered package, done here so the
 * platform can prove FR-6.5 ("any tampering must be detectable") without a separate tool.
 */
@Component
public class ExportVerifier {

    private final ObjectMapper json;

    public ExportVerifier(ObjectMapper json) {
        this.json = json;
    }

    public VerificationResult verify(byte[] packageBytes, String expectedPackageSha256) {
        boolean packageOk = sha256Hex(packageBytes).equalsIgnoreCase(expectedPackageSha256);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        } catch (IOException e) {
            return new VerificationResult(false, packageOk, List.of("package is not a readable zip: " + e.getMessage()), 0);
        }

        byte[] manifestBytes = entries.get("manifest.json");
        if (manifestBytes == null) {
            return new VerificationResult(false, packageOk, List.of("manifest.json is missing from the package"), 0);
        }

        Manifest manifest;
        try {
            manifest = json.readValue(manifestBytes, Manifest.class);
        } catch (Exception e) {
            return new VerificationResult(false, packageOk, List.of("manifest.json could not be parsed: " + e.getMessage()), 0);
        }

        List<String> mismatches = new ArrayList<>();
        java.util.Set<String> manifestPaths = new java.util.HashSet<>();
        for (ManifestItem item : manifest.items()) {
            manifestPaths.add(item.path());
            byte[] bytes = entries.get(item.path());
            if (bytes == null) {
                mismatches.add(item.path() + ": missing from the package");
                continue;
            }
            String actual = sha256Hex(bytes);
            if (!actual.equalsIgnoreCase(item.sha256())) {
                mismatches.add(item.path() + ": manifest says " + item.sha256() + ", package contains " + actual);
            }
        }

        // Entries in the zip that the manifest never mentions are just as much tampering as a
        // missing or altered one (FR-6.5) — without this check, an attacker who can also forge
        // the whole-package checksum (e.g. an insider with direct DB access to packageSha256)
        // could add arbitrary files and still pass verification, since the loop above only ever
        // checks what the manifest lists, never what the zip actually contains.
        for (String name : entries.keySet()) {
            if (!"manifest.json".equals(name) && !manifestPaths.contains(name)) {
                mismatches.add(name + ": present in the package but not listed in the manifest");
            }
        }

        boolean valid = packageOk && mismatches.isEmpty();
        return new VerificationResult(valid, packageOk, mismatches, manifest.items().size());
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
