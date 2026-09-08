package com.discoveryhub.archive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * One attachment. The bytes no longer live in the database: they are written to local disk (the
 * primary copy the read API serves) and, when S3 is enabled, offloaded to S3. The table keeps the
 * metadata, the chain-of-custody {@code sha256}, and pointers — {@code storage_location} for the
 * local file and {@code s3_key} / {@code s3_bucket} for the optional S3 copy.
 *
 * <p>{@code contentBytes} is {@link Transient}: set by {@link MessageMapper} while turning a wire
 * message into entities, read once by the storage service to write the blob, then cleared so the
 * bytes are not held in memory across the JPA save. It is never persisted.
 *
 * <p>{@code sha256} remains the chain-of-custody anchor that the export verifier re-computes
 * (FR-6.5); it now anchors bytes that live on disk / S3 instead of in the row.
 */
@Entity
@Table(name = "attachments")
public class AttachmentEntity {

    @Id
    @Column(name = "attachment_id", length = 36)
    private String attachmentId;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "content_type", length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    /** Relative path of the primary local copy, e.g. {@code <messageId>/<attachmentId>}. */
    @Column(name = "storage_location", nullable = false, length = 1024)
    private String storageLocation;

    /** S3 object key for the offload copy, or {@code null} when S3 is not enabled. */
    @Column(name = "s3_key", length = 1024)
    private String s3Key;

    /** S3 bucket that holds the offload copy, or {@code null} when S3 is not enabled. */
    @Column(name = "s3_bucket", length = 255)
    private String s3Bucket;

    /** In-memory only: the decoded bytes, present between mapping and storage, never persisted. */
    @Transient
    private byte[] contentBytes;

    protected AttachmentEntity() {
        // JPA
    }

    public String getAttachmentId() { return attachmentId; }
    public void setAttachmentId(String attachmentId) { this.attachmentId = attachmentId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getStorageLocation() { return storageLocation; }
    public void setStorageLocation(String storageLocation) { this.storageLocation = storageLocation; }

    public String getS3Key() { return s3Key; }
    public void setS3Key(String s3Key) { this.s3Key = s3Key; }

    public String getS3Bucket() { return s3Bucket; }
    public void setS3Bucket(String s3Bucket) { this.s3Bucket = s3Bucket; }

    public byte[] getContentBytes() { return contentBytes; }
    public void setContentBytes(byte[] contentBytes) { this.contentBytes = contentBytes; }
}
