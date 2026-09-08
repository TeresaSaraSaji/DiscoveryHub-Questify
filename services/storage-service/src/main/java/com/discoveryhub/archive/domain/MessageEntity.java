package com.discoveryhub.archive.domain;

import com.discoveryhub.contracts.MessageType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent form of an archived message. The system of record row.
 *
 * <p>Array fields ({@code to}, {@code cc}, {@code labels}) are stored as JSON text in
 * {@code to_list} / {@code cc_list} / {@code labels}; {@link MessageMapper} serialises and parses
 * them. They are never queried inside the database — filters run in P3's index — so a typed
 * Postgres array would buy nothing but portability pain.
 *
 * <p>{@code onHold} / {@code holdCount} mirror P4's hold state for fast local skipping. This flag
 * is an optimisation, not the guarantee: the disposition job still asks P4 synchronously before
 * deleting anything, because a stale flag here would destroy evidence (architecture decision 4).
 */
@Entity
@Table(name = "messages")
public class MessageEntity {

    @Id
    @Column(name = "message_id", length = 36)
    private String messageId;

    @Column(name = "external_id", nullable = false, length = 255)
    private String externalId;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private MessageType type;

    @Column(name = "custodian_id", nullable = false, length = 255)
    private String custodianId;

    @Column(name = "from_addr", nullable = false, length = 255)
    private String from;

    @Column(name = "to_list", nullable = false)
    private String to;

    @Column(name = "cc_list", nullable = false)
    private String cc;

    @Column(name = "subject")
    private String subject;

    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "thread_id", nullable = false, length = 36)
    private String threadId;

    @Column(name = "in_reply_to", length = 36)
    private String inReplyTo;

    @Column(name = "labels", nullable = false)
    private String labels;

    @Column(name = "on_hold", nullable = false)
    private boolean onHold;

    @Column(name = "hold_count", nullable = false)
    private int holdCount;

    @Column(name = "attachment_count", nullable = false)
    private int attachmentCount;

    @Column(name = "archived_at", nullable = false)
    private Instant archivedAt;

    protected MessageEntity() {
        // JPA
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getCustodianId() { return custodianId; }
    public void setCustodianId(String custodianId) { this.custodianId = custodianId; }

    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public String getCc() { return cc; }
    public void setCc(String cc) { this.cc = cc; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getInReplyTo() { return inReplyTo; }
    public void setInReplyTo(String inReplyTo) { this.inReplyTo = inReplyTo; }

    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }

    public boolean isOnHold() { return onHold; }
    public void setOnHold(boolean onHold) { this.onHold = onHold; }

    public int getHoldCount() { return holdCount; }
    public void setHoldCount(int holdCount) { this.holdCount = holdCount; }

    public int getAttachmentCount() { return attachmentCount; }
    public void setAttachmentCount(int attachmentCount) { this.attachmentCount = attachmentCount; }

    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }
}
