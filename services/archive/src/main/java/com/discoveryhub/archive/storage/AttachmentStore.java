package com.discoveryhub.archive.storage;

import com.discoveryhub.archive.domain.AttachmentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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

    /** Writes bytes (local + optional S3) and records the storage refs on the entity. */
    public void store(AttachmentEntity att) {
        byte[] bytes = att.getContentBytes();
        if (bytes == null) {
            bytes = new byte[0];
        }
        att.setStorageLocation(local.store(att.getMessageId(), att.getAttachmentId(), bytes));

        S3BlobStorage s3store = s3.getIfAvailable();
        if (s3store != null) {
            try {
                String key = s3store.store(att.getMessageId(), att.getAttachmentId(), bytes);
                att.setS3Key(key);
                att.setS3Bucket(s3store.bucket());
            } catch (RuntimeException ex) {
                // Local copy is the primary guarantee; a failed S3 offload is logged and skipped.
                log.warn("S3 offload failed for {}/{} — local copy stored, S3 skipped: {}",
                        att.getMessageId(), att.getAttachmentId(), ex.toString());
            }
        }

        // Bytes now live on disk (and S3); drop the in-memory copy so it is not held across the save.
        att.setContentBytes(null);
    }

    /** Loads bytes for an attachment: local first, S3 offload copy as a fallback. */
    public byte[] load(AttachmentEntity att) {
        try {
            return local.load(att.getStorageLocation());
        } catch (RuntimeException ex) {
            S3BlobStorage s3store = s3.getIfAvailable();
            if (att.getS3Key() != null && s3store != null) {
                log.info("local copy unavailable for {}/{} — serving S3 offload copy: {}",
                        att.getMessageId(), att.getAttachmentId(), ex.toString());
                return s3store.load(att.getS3Key());
            }
            throw ex;
        }
    }

    /** Deletes the local copy and, if present, the S3 offload copy. Best-effort. */
    public void delete(AttachmentEntity att) {
        try {
            local.delete(att.getStorageLocation());
        } catch (RuntimeException ex) {
            log.warn("failed deleting local attachment {}/{}: {}",
                    att.getMessageId(), att.getAttachmentId(), ex.toString());
        }
        S3BlobStorage s3store = s3.getIfAvailable();
        if (att.getS3Key() != null && s3store != null) {
            try {
                s3store.delete(att.getS3Key());
            } catch (RuntimeException ex) {
                log.warn("failed deleting S3 offload {}/{}: {}",
                        att.getMessageId(), att.getAttachmentId(), ex.toString());
            }
        }
    }
}
