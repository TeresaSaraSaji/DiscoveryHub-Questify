package com.discoveryhub.search.kafka;

import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.Topics;
import com.discoveryhub.search.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Removes a message from the search index once P2 reports it destroyed (topic
 * {@code disposition.results}).
 *
 * <p>Without this, disposition is only half done. P2 deletes the message and its attachments, the
 * audit trail records {@code disposition.deleted}, and the document stays in Elasticsearch — so a
 * message the system asserts it destroyed is still searchable, with its sender, subject and body
 * readable in the results. For a retention product that is the requirement inverted, and it is not
 * hypothetical: the index drifted 3,541 documents ahead of the archive before this existed.
 *
 * <p>It also had a second, quieter consequence. Evidence is filed onto a case from search results,
 * so every sweep widened the set of messages that could be attached to a matter without existing —
 * dead references that surface much later, as items an export cannot package.
 *
 * <p>Only an outcome that means "the archive does not hold this" removes anything:
 *
 * <ul>
 *   <li>{@link DeleteReceipt.Outcome#DELETED} — gone from the archive, so gone from here.</li>
 *   <li>{@link DeleteReceipt.Outcome#NOT_FOUND} — P2 has no such message, which for the index is
 *       the same fact arrived at differently (a replayed command, or a concurrent delete). Removing
 *       is idempotent, and a no-op for an id that was never indexed.</li>
 *   <li>{@link DeleteReceipt.Outcome#REFUSED_HOLD} and {@link DeleteReceipt.Outcome#FAILED} — the
 *       message is still in the archive and must stay searchable. A hold that saved a message from
 *       deletion must not cost it its discoverability; that would defeat the point of the hold.</li>
 * </ul>
 *
 * <p>Its own consumer rather than a branch inside {@link ArchivedMessageConsumer}: that one is
 * about indexing what P2 archived, this is about honouring what P2 destroyed, and they fail
 * independently.
 */
@Component
public class DisposedMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(DisposedMessageConsumer.class);

    private final SearchRepository repository;
    private final ObjectMapper json;

    public DisposedMessageConsumer(SearchRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @KafkaListener(topics = Topics.DISPOSITION_RESULTS, groupId = "p3-search",
            containerFactory = "searchKafkaListenerContainerFactory")
    public void onReceipt(String payload) {
        DeleteReceipt receipt;
        try {
            receipt = json.readValue(payload, DeleteReceipt.class);
        } catch (JacksonException ex) {
            // Logged and skipped rather than wedging the consumer, as on messages.archived.
            log.warn("skipping unparseable disposition.results payload: {}", ex.getMessage());
            return;
        }
        if (receipt.messageId() == null || receipt.outcome() == null) {
            log.warn("skipping incomplete delete receipt: {}", payload);
            return;
        }
        if (receipt.outcome() != DeleteReceipt.Outcome.DELETED
                && receipt.outcome() != DeleteReceipt.Outcome.NOT_FOUND) {
            log.debug("message {} survived disposition ({}); leaving it searchable",
                    receipt.messageId(), receipt.outcome());
            return;
        }
        try {
            repository.deleteByMessageId(receipt.messageId());
            log.info("removed disposed message {} from the search index", receipt.messageId());
        } catch (RuntimeException ex) {
            // Rethrown so the container's error handler sees it: a document left behind here is
            // destroyed data that is still searchable, which is worth a loud failure rather than a
            // swallowed one.
            log.error("failed to remove disposed message {} from the index: {}",
                    receipt.messageId(), ex.getMessage());
            throw ex;
        }
    }
}
