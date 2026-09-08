package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code messages.ingested} and hands each message to {@link ArchiveService}. On a store,
 * publishes {@code messages.archived} and a {@code message.archived} audit event; on a dedupe,
 * publishes a {@code message.deduped} audit event (dropping a re-send is normal, not an error —
 * message-schema.md). The unique constraint is the real guarantee, so a race past the fast-path
 * check is caught here and treated as a dedupe too.
 *
 * <p>The record arrives as a JSON string (see {@code application.yml} — Boot 4.1 ships Jackson 3,
 * but Spring Kafka's JsonDeserializer is built against Jackson 2, so we use String serdes and parse
 * here with the Jackson 3 ObjectMapper). A malformed record is logged and skipped rather than
 * wedging the consumer in a permanent retry loop.
 */
@Component
public class MessageIngestedListener {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestedListener.class);

    private final ArchiveService archive;
    private final com.discoveryhub.archive.domain.MessageMapper mapper;
    private final ArchiveKafkaPublisher publisher;
    private final AuditEvents audit;
    private final ObjectMapper json;

    public MessageIngestedListener(ArchiveService archive,
                                   com.discoveryhub.archive.domain.MessageMapper mapper,
                                   ArchiveKafkaPublisher publisher,
                                   AuditEvents audit,
                                   ObjectMapper json) {
        this.archive = archive;
        this.mapper = mapper;
        this.publisher = publisher;
        this.audit = audit;
        this.json = json;
    }

    @KafkaListener(topics = Topics.MESSAGES_INGESTED, groupId = "p2-archive")
    public void onIngested(String payload) {
        Message message;
        try {
            message = json.readValue(payload, Message.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable messages.ingested payload: {}", ex.getMessage());
            return;
        }
        try {
            IngestionResult result = archive.ingest(message);
            if (result.outcome() == IngestionOutcome.STORED) {
                Message archived = mapper.toArchived(result.entity(), result.attachments());
                publisher.publishArchived(archived);
                publisher.publishAudit(audit.archived(result.entity().getMessageId(), result.attachments().size()));
            } else {
                publisher.publishAudit(audit.deduped(result.externalId()));
            }
        } catch (DataIntegrityViolationException ex) {
            // The UNIQUE(external_id) constraint caught a race past existsByExternalId. This is the
            // idempotency guarantee working, not a failure: record a dedupe and move on.
            log.debug("deduped by constraint: externalId={}", message.externalId());
            publisher.publishAudit(audit.deduped(message.externalId()));
        }
    }
}
