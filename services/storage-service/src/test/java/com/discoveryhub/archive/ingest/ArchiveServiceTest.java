package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.config.RetentionProperties;
import com.discoveryhub.archive.domain.ArchivedMessageDocument;
import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.ArchivedMessageRepository;
import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idempotency guarantee (FR-1.6) is layered now: P1's message_id_map is the first line of
 * defence, and {@code UNIQUE(external_id)} on {@code message_hold_status} is P2's own backstop.
 * The fast path is the repository's {@code existsByExternalId} check; the hard guarantee is that
 * constraint, which {@link ArchiveService} itself turns into a dedupe outcome on a
 * {@code DataIntegrityViolationException}. These tests cover both paths.
 */
@ExtendWith(MockitoExtension.class)
class ArchiveServiceTest {

    @Mock ArchivedMessageRepository documents;
    @Mock MessageHoldStatusRepository holdStatuses;
    @Spy MessageMapper mapper = new MessageMapper(
            new RetentionProperties(null, Map.of(), Duration.ofMinutes(2)));

    @InjectMocks ArchiveService service;

    private Message message;

    @BeforeEach
    void setUp() {
        message = new Message(
                null, "EXCH-001", "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", List.of("to@x.com"), List.of(), "subj", "body",
                Instant.parse("2024-05-11T21:37:00Z"), "thread-1", null,
                List.of(), List.of());
    }

    @Test
    void storesFirstOccurrenceAndSavesBothStores() {
        when(holdStatuses.existsByExternalId("EXCH-001")).thenReturn(false);

        IngestionResult result = service.ingest(message);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.STORED);
        assertThat(result.document()).isNotNull();
        assertThat(result.document().externalId()).isEqualTo("EXCH-001");
        verify(documents, times(1)).save(any(ArchivedMessageDocument.class));
        verify(holdStatuses, times(1)).save(any(MessageHoldStatus.class));
    }

    @Test
    void dedupesSecondOccurrenceOfSameExternalId() {
        when(holdStatuses.existsByExternalId("EXCH-001")).thenReturn(true);

        IngestionResult result = service.ingest(message);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.DEDUPED);
        assertThat(result.externalId()).isEqualTo("EXCH-001");
        assertThat(result.document()).isNull();
        // A dedupe must not write anything.
        verify(documents, never()).save(any(ArchivedMessageDocument.class));
        verify(holdStatuses, never()).save(any(MessageHoldStatus.class));
    }

    @Test
    void dedupesWhenTheConstraintCatchesARacePastTheFastPathCheck() {
        when(holdStatuses.existsByExternalId("EXCH-001")).thenReturn(false);
        when(holdStatuses.save(any(MessageHoldStatus.class)))
                .thenThrow(new DataIntegrityViolationException("uq_message_hold_status_external_id"));

        IngestionResult result = service.ingest(message);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.DEDUPED);
        assertThat(result.externalId()).isEqualTo("EXCH-001");
        // The Mongo document was already written before the race was discovered — same content,
        // same messageId, so nothing to undo.
        verify(documents, times(1)).save(any(ArchivedMessageDocument.class));
    }
}
