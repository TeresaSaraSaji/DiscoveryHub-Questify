package com.discoveryhub.archive.storage;

/**
 * A byte-blob backend. Both the local-disk store and the S3 store implement this contract so the
 * orchestrator ({@link AttachmentStore}) can treat them uniformly.
 */
public interface BlobStorage {

    /**
     * Persist {@code bytes} for the attachment identified by {@code messageId}/{@code attachmentId}.
     *
     * @return the location key that {@link #load(String)} / {@link #delete(String)} expect later
     *         (a relative path for the local store, an object key for the S3 store).
     */
    String store(String messageId, String attachmentId, byte[] bytes);

    /** Load the bytes previously written at {@code location}. */
    byte[] load(String location);

    /** Remove the blob at {@code location}. Best-effort: a missing blob is not an error. */
    void delete(String location);
}
