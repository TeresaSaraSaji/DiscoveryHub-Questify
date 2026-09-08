package com.discoveryhub.export.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-6.5: tampering must be detectable. These tests build a package the same shape
 * {@link PackageBuilder} produces and confirm the verifier both passes a genuine package and
 * catches every way a delivered one could have been altered.
 */
class ExportVerifierTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ExportVerifier verifier = new ExportVerifier(json);

    @Test
    void validPackagePassesEveryCheck() throws IOException {
        byte[] messageBytes = "{\"messageId\":\"m-1\"}".getBytes();
        Built built = buildPackage(List.of(
                ManifestItem.message("m-1", "messages/m-1.json", sha256(messageBytes), messageBytes.length)),
                List.of("messages/m-1.json"), List.of(messageBytes));

        VerificationResult result = verifier.verify(built.bytes(), built.packageSha256());

        assertThat(result.valid()).isTrue();
        assertThat(result.packageChecksumMatches()).isTrue();
        assertThat(result.itemMismatches()).isEmpty();
        assertThat(result.itemsChecked()).isEqualTo(1);
    }

    @Test
    void tamperedItemBytesAreDetected() throws IOException {
        byte[] originalBytes = "{\"messageId\":\"m-1\"}".getBytes();
        Built built = buildPackage(List.of(
                ManifestItem.message("m-1", "messages/m-1.json", sha256(originalBytes), originalBytes.length)),
                List.of("messages/m-1.json"), List.of(originalBytes));

        byte[] tampered = swapEntry(built.bytes(), "messages/m-1.json", "{\"messageId\":\"m-1-EDITED\"}".getBytes());

        VerificationResult result = verifier.verify(tampered, built.packageSha256());

        assertThat(result.valid()).isFalse();
        assertThat(result.itemMismatches()).hasSize(1);
        assertThat(result.itemMismatches().getFirst()).contains("messages/m-1.json");
    }

    @Test
    void packageLevelTamperingIsDetectedEvenIfItemsStillMatch() throws IOException {
        byte[] messageBytes = "{\"messageId\":\"m-1\"}".getBytes();
        Built built = buildPackage(List.of(
                ManifestItem.message("m-1", "messages/m-1.json", sha256(messageBytes), messageBytes.length)),
                List.of("messages/m-1.json"), List.of(messageBytes));

        // The package checksum on record no longer matches the (unmodified) bytes — simulating a
        // job row that was tampered with independently of the package itself.
        VerificationResult result = verifier.verify(built.bytes(), "0".repeat(64));

        assertThat(result.valid()).isFalse();
        assertThat(result.packageChecksumMatches()).isFalse();
        assertThat(result.itemMismatches()).isEmpty();
    }

    @Test
    void missingManifestFailsCleanly() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("messages/m-1.json"));
            zip.write("{}".getBytes());
            zip.closeEntry();
        }
        byte[] bytes = buffer.toByteArray();

        VerificationResult result = verifier.verify(bytes, sha256(bytes));

        assertThat(result.valid()).isFalse();
        assertThat(result.itemMismatches()).anyMatch(m -> m.contains("manifest.json"));
    }

    private Built buildPackage(List<ManifestItem> items, List<String> paths, List<byte[]> contents) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (int i = 0; i < paths.size(); i++) {
                zip.putNextEntry(new ZipEntry(paths.get(i)));
                zip.write(contents.get(i));
                zip.closeEntry();
            }
            Manifest manifest = new Manifest("job-1", "case-1", Instant.parse("2024-01-01T00:00:00Z"), items);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(json.writeValueAsBytes(manifest));
            zip.closeEntry();
        }
        byte[] bytes = buffer.toByteArray();
        return new Built(bytes, sha256(bytes));
    }

    /** Rebuilds the zip with one entry's content swapped, leaving every other entry byte-identical. */
    private byte[] swapEntry(byte[] original, String targetPath, byte[] replacement) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(original));
             ZipOutputStream out = new ZipOutputStream(buffer)) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                byte[] content = entry.getName().equals(targetPath) ? replacement : in.readAllBytes();
                out.putNextEntry(new ZipEntry(entry.getName()));
                out.write(content);
                out.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Built(byte[] bytes, String packageSha256) {
    }
}
