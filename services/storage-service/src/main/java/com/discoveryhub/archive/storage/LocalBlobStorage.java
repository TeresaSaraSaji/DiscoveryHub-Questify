package com.discoveryhub.archive.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Primary attachment store: local disk. One file per attachment at
 * {@code <baseDir>/<messageId>/<attachmentId>}. The deterministic path makes a Kafka re-delivery
 * idempotent — re-storing the same attachment overwrites the same file rather than orphaning a
 * second copy.
 *
 * <p>{@code storage_location} stored in the DB is the <i>relative</i> path
 * {@code <messageId>/<attachmentId>}; this class resolves it against {@code baseDir} and guards
 * against a location that tries to escape the base directory.
 */
@Component
public class LocalBlobStorage implements BlobStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalBlobStorage.class);

    private final Path baseDir;

    public LocalBlobStorage(StorageProperties props) {
        this.baseDir = Paths.get(props.local().baseDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot create attachment directory " + baseDir, ex);
        }
        log.info("local attachment storage at {}", baseDir);
    }

    @Override
    public String store(String messageId, String attachmentId, byte[] bytes) {
        // messageId/attachmentId come off the wire (messages.ingested), not from a trusted
        // internal source — validate them the same way resolve() validates a stored location,
        // so a value containing ".." cannot write outside baseDir before load()/delete() ever get
        // a chance to reject it on the read side.
        String location = messageId + "/" + attachmentId;
        Path file = resolve(location);
        Path dir = file.getParent();
        try {
            Files.createDirectories(dir);
            Files.write(file, bytes == null ? new byte[0] : bytes);
            return location;
        } catch (IOException ex) {
            throw new UncheckedIOException("failed writing attachment " + file, ex);
        }
    }

    @Override
    public byte[] load(String location) {
        Path file = resolve(location);
        if (!Files.exists(file)) {
            throw new UncheckedIOException(new NoSuchFileException(file.toString()));
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed reading attachment " + file, ex);
        }
    }

    @Override
    public void delete(String location) {
        Path file = resolve(location);
        try {
            Files.deleteIfExists(file);
            // Best-effort: prune the now-empty message directory.
            Path dir = file.getParent();
            if (dir != null && !dir.equals(baseDir)) {
                try (var stream = Files.list(dir)) {
                    if (stream.findAny().isEmpty()) {
                        Files.deleteIfExists(dir);
                    }
                }
            }
        } catch (IOException ex) {
            log.warn("failed deleting attachment {}: {}", file, ex.toString());
        }
    }

    private Path resolve(String location) {
        Objects.requireNonNull(location, "storage location");
        Path resolved = baseDir.resolve(location).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("storage location escapes base dir: " + location);
        }
        return resolved;
    }
}
