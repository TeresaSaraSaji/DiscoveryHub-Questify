package com.discoveryhub.search.model;

import com.discoveryhub.contracts.MessageType;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;
import java.util.List;

/**
 * The Elasticsearch representation of one archived message. Built from the {@code messages.archived}
 * event by {@link com.discoveryhub.search.mapper.CommunicationDocumentMapper}.
 *
 * <p>The attachment bytes are deliberately not here — once P2 has written them to object storage,
 * {@code sha256} is the chain-of-custody anchor and the bytes never appear in search results,
 * in P2's read API, or in audit events (message-schema.md). We keep only the count and the
 * filenames so a reviewer can see what a hit carried without being able to reconstruct it from
 * the index.
 *
 * <p>{@code onHold} is mirrored here from {@code holds.events} so a held message can be flagged in
 * results. It is an optimisation, not the guarantee: a held message is still indexed and still
 * searchable, because a hold is about preservation, not suppression.
 */
@Document(indexName = "communications")
public class CommunicationDocument {

    /** DiscoveryHub message id — the primary key in the index. Derived from externalId, never random. */
    @Id
    @Field(type = FieldType.Keyword)
    private String messageId;

    @Field(type = FieldType.Keyword)
    private String externalId;

    @Field(type = FieldType.Keyword)
    private String source;

    @Field(type = FieldType.Keyword)
    private MessageType type;

    /** Owner of the mailbox this copy came from — not the sender (message-schema.md). */
    @Field(type = FieldType.Keyword)
    private String custodianId;

    @Field(type = FieldType.Keyword)
    private String from;

    /** Searched as text; filtered as keyword when a hit needs to be addressed. */
    @Field(type = FieldType.Text)
    private List<String> to;

    @Field(type = FieldType.Text)
    private List<String> cc;

    @Field(type = FieldType.Text, name = "subject")
    private String subject;

    /** The body of the message — the thing full-text search is for. */
    @Field(type = FieldType.Text, name = "body")
    private String body;

    @Field(type = FieldType.Date, format = DateFormat.strict_date_time, name = "sentAt")
    private Instant sentAt;

    @Field(type = FieldType.Keyword)
    private String threadId;

    @Field(type = FieldType.Keyword)
    private String inReplyTo;

    @Field(type = FieldType.Integer)
    private int attachmentCount;

    @Field(type = FieldType.Keyword)
    private List<String> attachmentFilenames;

    @Field(type = FieldType.Keyword)
    private List<String> labels;

    /** Mirrored from holds.events; true while any hold covers this message. */
    @Field(type = FieldType.Boolean)
    private boolean onHold;

    /** Spring Data Elasticsearch instantiates via reflection, so a no-arg constructor is required. */
    public CommunicationDocument() {
    }

    public CommunicationDocument(
            String messageId, String externalId, String source, MessageType type,
            String custodianId, String from, List<String> to, List<String> cc, String subject,
            String body, Instant sentAt, String threadId, String inReplyTo, int attachmentCount,
            List<String> attachmentFilenames, List<String> labels, boolean onHold) {
        this.messageId = messageId;
        this.externalId = externalId;
        this.source = source;
        this.type = type;
        this.custodianId = custodianId;
        this.from = from;
        this.to = to;
        this.cc = cc;
        this.subject = subject;
        this.body = body;
        this.sentAt = sentAt;
        this.threadId = threadId;
        this.inReplyTo = inReplyTo;
        this.attachmentCount = attachmentCount;
        this.attachmentFilenames = attachmentFilenames;
        this.labels = labels;
        this.onHold = onHold;
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

    public List<String> getTo() { return to; }
    public void setTo(List<String> to) { this.to = to; }

    public List<String> getCc() { return cc; }
    public void setCc(List<String> cc) { this.cc = cc; }

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

    public int getAttachmentCount() { return attachmentCount; }
    public void setAttachmentCount(int attachmentCount) { this.attachmentCount = attachmentCount; }

    public List<String> getAttachmentFilenames() { return attachmentFilenames; }
    public void setAttachmentFilenames(List<String> attachmentFilenames) { this.attachmentFilenames = attachmentFilenames; }

    public List<String> getLabels() { return labels; }
    public void setLabels(List<String> labels) { this.labels = labels; }

    public boolean isOnHold() { return onHold; }
    public void setOnHold(boolean onHold) { this.onHold = onHold; }
}
