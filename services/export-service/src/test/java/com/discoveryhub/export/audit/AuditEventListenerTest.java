package com.discoveryhub.export.audit;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.export.domain.AuditLogEntity;
import com.discoveryhub.export.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-7: every audit event is appended exactly once, and a replay of one already recorded must
 * never surface as an error to the consumer — Kafka's at-least-once delivery makes replay routine,
 * not exceptional.
 */
@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

    @Mock AuditLogRepository repository;

    private final ObjectMapper json = new ObjectMapper();
    private AuditEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new AuditEventListener(repository, json);
    }

    @Test
    void parsesAndPersistsAWellFormedEvent() throws Exception {
        AuditEvent event = new AuditEvent("evt-1", Instant.parse("2024-05-11T21:37:00Z"), "P1",
                "message.ingested", AuditEvent.Outcome.SUCCESS, "message", "msg-1", "system:ingestion",
                "corr-1", Map.of("externalId", "EXCH-1"));

        listener.onAuditEvent(json.writeValueAsString(event));

        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(repository, times(1)).save(captor.capture());
        AuditLogEntity saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo("evt-1");
        assertThat(saved.getAction()).isEqualTo("message.ingested");
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
        assertThat(saved.getDetail()).contains("EXCH-1");
    }

    @Test
    void aReplayedEventIsAbsorbedNotPropagatedAsAnError() throws Exception {
        AuditEvent event = new AuditEvent("evt-2", Instant.now(), "P2", "message.archived",
                AuditEvent.Outcome.SUCCESS, "message", "msg-2", "system", "corr-2", Map.of());
        when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatCode(() -> listener.onAuditEvent(json.writeValueAsString(event))).doesNotThrowAnyException();
    }

    @Test
    void unparseablePayloadIsSkippedNotThrown() {
        assertThatCode(() -> listener.onAuditEvent("not json")).doesNotThrowAnyException();
    }
}
