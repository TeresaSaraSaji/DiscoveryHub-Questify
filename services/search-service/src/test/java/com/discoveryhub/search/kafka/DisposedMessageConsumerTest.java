package com.discoveryhub.search.kafka;

import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.search.repository.SearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Honouring P2's delete receipts in the search index.
 *
 * <p>The property being protected is not subtle: a message the system asserts it destroyed must
 * not still be searchable. Before this consumer existed the index drifted 3,541 documents ahead of
 * the archive, and a disposed message's sender, subject and body were all still readable through
 * search — with the audit trail simultaneously recording it as deleted.
 *
 * <p>The inverse matters just as much and is easier to get wrong. {@code REFUSED_HOLD} means a
 * hold saved the message from deletion; removing it from the index on that receipt would cost a
 * held message its discoverability, which is the opposite of what the hold is for. So the tests
 * below pin both directions, per outcome.
 *
 * <p>Driven through the JSON payload rather than a parsed object, because the payload is what
 * Kafka actually delivers — String serdes, parsed here with the Jackson 3 mapper Boot
 * auto-configures — and a receipt that cannot be read is one of the ways this consumer can fail
 * quietly.
 */
@ExtendWith(MockitoExtension.class)
class DisposedMessageConsumerTest {

    @Mock SearchRepository repository;

    private final ObjectMapper json = new ObjectMapper();

    private DisposedMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DisposedMessageConsumer(repository, json);
    }

    @Test
    void aConfirmedDeleteRemovesTheDocumentFromTheIndex() {
        consumer.onReceipt(payload(DeleteReceipt.Outcome.DELETED, "msg-1"));

        verify(repository).deleteByMessageId("msg-1");
    }

    /**
     * {@code NOT_FOUND} is the same fact arrived at differently — P2 has no such message, so
     * neither should search. Removing is idempotent and a no-op for an id that was never indexed,
     * which is what makes a replayed command harmless.
     */
    @Test
    void aNotFoundReceiptAlsoRemovesIt() {
        consumer.onReceipt(payload(DeleteReceipt.Outcome.NOT_FOUND, "msg-2"));

        verify(repository).deleteByMessageId("msg-2");
    }

    /**
     * The one that would be a real bug. A hold landed between the sweep's check and P2's delete,
     * so the message is still in the archive — taking it out of the index would make a held
     * message undiscoverable, defeating the hold it was saved by.
     */
    @Test
    void aRefusalLeavesTheMessageSearchable() {
        consumer.onReceipt(payload(DeleteReceipt.Outcome.REFUSED_HOLD, "msg-3"));

        verify(repository, never()).deleteByMessageId(anyString());
    }

    /** A transient failure on P2's side: the message is still there and the next sweep retries. */
    @Test
    void aFailedDeleteLeavesTheMessageSearchable() {
        consumer.onReceipt(payload(DeleteReceipt.Outcome.FAILED, "msg-4"));

        verify(repository, never()).deleteByMessageId(anyString());
    }

    @Test
    void anUnparseablePayloadIsSkippedRatherThanWedgingTheConsumer() {
        consumer.onReceipt("{not json");

        verify(repository, never()).deleteByMessageId(anyString());
    }

    /**
     * A receipt missing the two fields this consumer turns into a decision is not actionable.
     * Skipped rather than guessed at — deleting on an absent outcome would remove a document on
     * the strength of a malformed record.
     */
    @Test
    void anIncompleteReceiptIsSkipped() {
        consumer.onReceipt("{\"runId\":\"run-1\",\"outcome\":\"DELETED\"}");
        consumer.onReceipt("{\"runId\":\"run-1\",\"messageId\":\"msg-5\"}");

        verify(repository, never()).deleteByMessageId(anyString());
    }

    /**
     * Rethrown so the container's error handler sees it. A document left behind here is destroyed
     * data that is still searchable, which is worth a loud failure and a redelivery rather than a
     * swallowed log line.
     */
    @Test
    void anIndexFailureIsRethrownRatherThanSwallowed() {
        doThrow(new IllegalStateException("elasticsearch unreachable"))
                .when(repository).deleteByMessageId("msg-6");

        assertThatThrownBy(() -> consumer.onReceipt(payload(DeleteReceipt.Outcome.DELETED, "msg-6")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("elasticsearch unreachable");
    }

    private String payload(DeleteReceipt.Outcome outcome, String messageId) {
        return json.writeValueAsString(new DeleteReceipt("run-1", messageId, "EXCH-1", outcome,
                "past retention", Instant.parse("2026-09-11T10:00:00Z")));
    }
}
