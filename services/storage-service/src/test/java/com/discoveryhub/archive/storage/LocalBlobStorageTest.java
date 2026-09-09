package com.discoveryhub.archive.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The primary attachment store. These are real filesystem round trips against a {@link TempDir} —
 * mocking {@code Files} would test nothing, and the properties that matter here (deterministic
 * paths, overwrite-on-redelivery, refusing to read outside the base directory) are all filesystem
 * behaviour.
 */
class LocalBlobStorageTest {

    @TempDir Path tmp;

    private LocalBlobStorage storage;

    private static StorageProperties propsFor(Path baseDir) {
        return new StorageProperties(new StorageProperties.Local(baseDir.toString()), null);
    }

    @BeforeEach
    void setUp() {
        storage = new LocalBlobStorage(propsFor(tmp.resolve("attachments")));
    }

    @Test
    void createsBaseDirectoryOnStartupSoTheFirstStoreDoesNotFail() {
        assertThat(tmp.resolve("attachments")).exists().isDirectory();
    }

    @Test
    void storeReturnsRelativeLocationAndRoundTripsBytes() {
        byte[] bytes = "invoice contents".getBytes(StandardCharsets.UTF_8);

        String location = storage.store("m-1", "att-1", bytes);

        // The DB stores the RELATIVE path, so the base directory can move between deployments
        // without rewriting every attachment row.
        assertThat(location).isEqualTo("m-1/att-1");
        assertThat(tmp.resolve("attachments/m-1/att-1")).exists();
        assertThat(storage.load(location)).isEqualTo(bytes);
    }

    @Test
    void storeIsIdempotentSoAKafkaRedeliveryOverwritesRatherThanOrphans() {
        String first = storage.store("m-1", "att-1", "v1".getBytes(StandardCharsets.UTF_8));
        String second = storage.store("m-1", "att-1", "v2".getBytes(StandardCharsets.UTF_8));

        assertThat(second).isEqualTo(first);
        assertThat(storage.load(second)).isEqualTo("v2".getBytes(StandardCharsets.UTF_8));
        // One file, not two: a redelivered record must not leave a second copy behind.
        assertThat(tmp.resolve("attachments/m-1")).isDirectoryContaining(p -> p.getFileName().toString().equals("att-1"));
        assertThat(countFiles(tmp.resolve("attachments/m-1"))).isEqualTo(1);
    }

    @Test
    void storeHandlesAZeroByteAttachment() {
        String location = storage.store("m-1", "empty", new byte[0]);

        assertThat(storage.load(location)).isEmpty();
    }

    @Test
    void storeTreatsNullBytesAsEmptyRatherThanThrowing() {
        String location = storage.store("m-1", "nulls", null);

        assertThat(storage.load(location)).isEmpty();
    }

    @Test
    void separateMessagesGetSeparateDirectories() {
        storage.store("m-1", "att-1", "a".getBytes(StandardCharsets.UTF_8));
        storage.store("m-2", "att-1", "b".getBytes(StandardCharsets.UTF_8));

        assertThat(storage.load("m-1/att-1")).isEqualTo("a".getBytes(StandardCharsets.UTF_8));
        assertThat(storage.load("m-2/att-1")).isEqualTo("b".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void loadOfAMissingBlobFails() {
        // The caller (AttachmentStore) turns this into an S3 fallback, so it must be an exception
        // and not an empty array — silently serving zero bytes would corrupt an export manifest.
        assertThatThrownBy(() -> storage.load("m-1/nope"))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void loadRefusesALocationThatEscapesTheBaseDirectory() {
        assertThatThrownBy(() -> storage.load("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes base dir");
    }

    @Test
    void loadRejectsANullLocation() {
        assertThatThrownBy(() -> storage.load(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void deleteRemovesTheFileAndPrunesTheEmptyMessageDirectory() {
        String location = storage.store("m-1", "att-1", "bytes".getBytes(StandardCharsets.UTF_8));

        storage.delete(location);

        assertThat(tmp.resolve("attachments/m-1/att-1")).doesNotExist();
        assertThat(tmp.resolve("attachments/m-1")).doesNotExist();
        // The base directory itself must survive, or the next store fails.
        assertThat(tmp.resolve("attachments")).exists();
    }

    @Test
    void deleteKeepsTheMessageDirectoryWhileSiblingAttachmentsRemain() {
        storage.store("m-1", "att-1", "a".getBytes(StandardCharsets.UTF_8));
        storage.store("m-1", "att-2", "b".getBytes(StandardCharsets.UTF_8));

        storage.delete("m-1/att-1");

        assertThat(tmp.resolve("attachments/m-1")).exists();
        assertThat(storage.load("m-1/att-2")).isEqualTo("b".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deleteOfAMissingBlobIsNotAnError() {
        // Disposition must not be blocked by a blob that is already gone.
        storage.delete("m-1/never-existed");
    }

    @Test
    void deleteIsIdempotent() {
        String location = storage.store("m-1", "att-1", "bytes".getBytes(StandardCharsets.UTF_8));

        storage.delete(location);
        storage.delete(location);

        assertThat(tmp.resolve("attachments/m-1/att-1")).doesNotExist();
    }

    private static long countFiles(Path dir) {
        try (var s = Files.list(dir)) {
            return s.count();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
