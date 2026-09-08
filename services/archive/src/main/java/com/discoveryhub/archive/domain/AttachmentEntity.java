package com.discoveryhub.archive.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One attachment, bytes included. Per the storage-service brief the content lives in PostgreSQL
 * ({@code content} BYTEA) rather than an object store; {@code sha256} is the chain-of-custody
 * anchor that P5 re-computes when building an export manifest (FR-6.5).
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

    @Column(name = "content", nullable = false)
    private byte[] content;

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

    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
}
