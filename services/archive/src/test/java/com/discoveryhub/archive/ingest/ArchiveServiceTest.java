package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Spy;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idempotency guarantee (FR-1.6) is what the whole dedupe story rests on. The fast path is the
 * repository's {@code existsByExternalId} check; the hard guarantee is the UNIQUE constraint, which
 * the listener turns into a dedupe outcome on a {@code DataIntegrityViolationException}. These
 * tests cover the fast path — the constraint path is an integration concern.
 */
@ExtendWith(MockitoExtension.class)
class ArchiveServiceTest {

    @Mock MessageRepository messages;
    @Mock AttachmentRepository attachments;
    @Spy MessageMapper mapper = new MessageMapper(new ObjectMapper());

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
    void storesFirstOccurrenceAndSavesEntities() {
        when(messages.existsByExternalId("EXCH-001")).thenReturn(false);

        IngestionResult result = service.ingest(message);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.STORED);
        assertThat(result.entity()).isNotNull();
        assertThat(result.entity().getExternalId()).isEqualTo("EXCH-001");
        verify(messages, times(1)).save(any(MessageEntity.class));
        verify(attachments, never()).saveAll(any());
    }

    @Test
    void dedupesSecondOccurrenceOfSameExternalId() {
        when(messages.existsByExternalId("EXCH-001")).thenReturn(true);

        IngestionResult result = service.ingest(message);

        assertThat(result.outcome()).isEqualTo(IngestionOutcome.DEDUPED);
        assertThat(result.externalId()).isEqualTo("EXCH-001");
        assertThat(result.entity()).isNull();
        // A dedupe must not write anything.
        verify(messages, never()).save(any(MessageEntity.class));
        verify(attachments, never()).saveAll(any());
    }
}
