package com.discoveryhub.archive.storage;

import com.discoveryhub.archive.domain.AttachmentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/**
 * Orchestrates attachment byte storage:
 * <ul>
 *   <li><b>store</b> — always writes the primary copy to local disk; when S3 is enabled, also
 *       writes an "after use" offload copy to S3. Records the local {@code storage_location} and,
 *       when S3 is enabled, the {@code s3_key} / {@code s3_bucket} on the entity.</li>
 *   <li><b>load</b> — serves the local copy; if it is unavailable but an S3 offload copy exists,
 *       serves that (S3 is the durable fallback for after the local copy has aged out).</li>
 *   <li><b>delete</b> — removes the local copy and, if present, the S3 offload copy.</li>
 * </ul>
 *
 * <p>S3 write/delete failures are best-effort: the local copy is the primary guarantee, so an S3
 * outage must not wedge ingestion. A failed S3 offload leaves {@code s3_key} null on the row, so a
 * later read will not try an S3 copy that was never written.
 */
@Service
public class AttachmentStore {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStore.class);

    private final LocalBlobStorage local;
    private final ObjectProvider<S3BlobStorage> s3;

    public AttachmentStore(LocalBlobStorage local, ObjectProvider<S3BlobStorage> s3) {
        this.local = local;
        this.s3 = s3;
    }

    /**
     * Where the bytes for one attachment ended up. {@code s3Key} / {@code s3Bucket} are null when
     * S3 is disabled or the offload failed.
     */
    public record StoredRefs(String storageLocation, String s3Key, String s3Bucket) {
    }

    /**
     * Writes bytes for one attachment: local disk always, S3 offload when enabled. The local write
     * is the primary guarantee, so its failure propagates (the Kafka error handler retries rather
     * than committing an offset for bytes that were never written); an S3 failure is logged and the
     * returned {@code s3Key} stays null so a later read does not chase a copy that does not exist.
     *
     * <p>Separate from {@link #store(AttachmentEntity)} because the backfill migration has raw rows
     * rather than entities, and both paths must place bytes identically.
     */
    public StoredRefs storeBytes(String messageId, String attachmentId, byte[] bytes) {
        byte[] safe = bytes == null ? new byte[0] : bytes;
        String location = local.store(messageId, attachmentId, safe);

        S3BlobStorage s3store = s3.getIfAvailable();
        if (s3store != null) {
            try {
                return new StoredRefs(location, s3store.store(messageId, attachmentId, safe), s3store.bucket());
            } catch (RuntimeException ex) {
                // Local copy is the primary guarantee; a failed S3 offload is logged and skipped.
                log.warn("S3 offload failed for {}/{} — local copy stored, S3 skipped: {}",
                        messageId, attachmentId, ex.toString());
            }
        }
        return new StoredRefs(location, null, null);
    }

    /** Writes bytes (local + optional S3) and records the storage refs on the entity. */
    public void store(AttachmentEntity att) {
        StoredRefs refs = storeBytes(att.getMessageId(), att.getAttachmentId(), att.getContentBytes());
        att.setStorageLocation(refs.storageLocation());
        att.setS3Key(refs.s3Key());
        att.setS3Bucket(refs.s3Bucket());

        // Bytes now live on disk (and S3); drop the in-memory copy so it is not held across the save.
        att.setContentBytes(null);
    }

    /** Loads bytes for an attachment: local first, S3 offload copy as a fallback. */
    public byte[] load(AttachmentEntity att) {
        return load(att.getStorageLocation(), att.getS3Key());
    }

    /**
     * Loads bytes from the local copy, falling back to the S3 offload copy at {@code s3Key} when the
     * local one is unavailable (it may have aged out) and there is one to fall back to.
     *
     * <p>Never degrades to an empty result: a missing blob throws, because serving zero bytes would
     * silently produce an export whose sha256 does not verify.
     */
    public byte[] load(String storageLocation, String s3Key) {
        try {
            return local.load(storageLocation);
        } catch (RuntimeException ex) {
            S3BlobStorage s3store = s3.getIfAvailable();
            if (s3Key != null && s3store != null) {
                log.info("local copy unavailable at {} — serving S3 offload copy {}: {}",
                        storageLocation, s3Key, ex.toString());
                return s3store.load(s3Key);
            }
            throw ex;
        }
    }

    /** Deletes the local copy and, if present, the S3 offload copy. Best-effort. */
    public void delete(AttachmentEntity att) {
        deleteBytes(refsOf(att));
    }

    /**
     * Schedules the blobs for these attachments to be deleted <b>after the current transaction
     * commits</b>, or immediately when there is no transaction in progress.
     *
     * <p>Deleting bytes is not transactional, so doing it inline before the rows go means a
     * rollback restores rows whose bytes are already gone — metadata that claims an attachment
     * exists when it does not, which is unrecoverable and looks like a clean rollback. Deferring to
     * after the commit inverts the failure: if the row delete rolls back the bytes are still there,
     * and if blob deletion fails after the commit the bytes are merely orphaned. An orphan wastes
     * disk and can be reconciled later; a missing blob behind a live row cannot be undone.
     *
     * <p>Refs are snapshotted now because the entities are removed by the time the callback runs.
     */
    public void deleteAfterCommit(Collection<AttachmentEntity> atts) {
        List<StoredRefs> refs = atts.stream().map(AttachmentStore::refsOf).toList();
        if (refs.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            refs.forEach(this::deleteBytes);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                refs.forEach(AttachmentStore.this::deleteBytes);
            }
        });
    }

    /** Removes both copies of one blob. Best-effort: a stuck blob store never blocks a deletion. */
    public void deleteBytes(StoredRefs refs) {
        try {
            local.delete(refs.storageLocation());
        } catch (RuntimeException ex) {
            log.warn("failed deleting local attachment {}: {}", refs.storageLocation(), ex.toString());
        }
        S3BlobStorage s3store = s3.getIfAvailable();
        if (refs.s3Key() != null && s3store != null) {
            try {
                s3store.delete(refs.s3Key());
            } catch (RuntimeException ex) {
                log.warn("failed deleting S3 offload {}: {}", refs.s3Key(), ex.toString());
            }
        }
    }

    private static StoredRefs refsOf(AttachmentEntity att) {
        return new StoredRefs(att.getStorageLocation(), att.getS3Key(), att.getS3Bucket());
    }
}
