package com.discoveryhub.search.kafka;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.Topics;
import com.discoveryhub.search.mapper.CommunicationDocumentMapper;
import com.discoveryhub.search.model.CommunicationDocument;
import com.discoveryhub.search.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code messages.archived} (from P2) and indexes each message into Elasticsearch so the
 * whole archive is searchable.
 *
 * <p>The record arrives as a JSON string (see {@code application.yml} — Boot 4.1 ships Jackson 3,
 * but Spring Kafka's JsonDeserializer is built against Jackson 2, so we use String serdes and parse
 * here with the Jackson 3 ObjectMapper Boot auto-configures). A malformed record is logged and
 * skipped rather than wedging the consumer, exactly as P2 does on the same topic.
 *
 * <p>The archived shape already has attachment {@code contentBase64} dropped (message-schema.md), so
 * nothing here touches bytes — the mapper keeps only filenames and the count.
 */
@Component
public class ArchivedMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ArchivedMessageConsumer.class);

    private final SearchRepository repository;
    private final CommunicationDocumentMapper mapper;
    private final ObjectMapper json;

    public ArchivedMessageConsumer(SearchRepository repository,
                                   CommunicationDocumentMapper mapper,
                                   ObjectMapper json) {
        this.repository = repository;
        this.mapper = mapper;
        this.json = json;
    }

    @KafkaListener(topics = Topics.MESSAGES_ARCHIVED, groupId = "p3-search",
            containerFactory = "searchKafkaListenerContainerFactory")
    public void onArchived(String payload) {
        Message message;
        try {
            message = json.readValue(payload, Message.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable messages.archived payload: {}", ex.getMessage());
            return;
        }
        try {
            CommunicationDocument document = mapper.fromMessage(message);
            repository.index(document);
        } catch (RuntimeException ex) {
            // An indexing failure is an infrastructure problem the consumer cannot fix here; let
            // the container's error handler log and seek past it so the consumer keeps moving.
            log.error("failed to index message {}: {}", message.messageId(), ex.getMessage());
            throw ex;
        }
    }
}
