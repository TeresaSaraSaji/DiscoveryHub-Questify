package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.config.RetentionProperties;
import com.discoveryhub.archive.domain.DispositionItemEntity;
import com.discoveryhub.archive.domain.DispositionOutcome;
import com.discoveryhub.archive.domain.DispositionRunEntity;
import com.discoveryhub.archive.domain.DispositionStatus;
import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.DispositionItemRepository;
import com.discoveryhub.archive.repository.DispositionRunRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Disposition must fail closed around the hold check (FR-4.2, FR-5.2): a message that P4 says is
 * held is skipped, and a message P4 cannot be asked about is also skipped — never deleted. These
 * tests pin that behaviour against a stubbed {@link HoldCheckClient} so the network is not on the
 * critical path of the test.
 *
 * <p>Test entities are built through {@link MessageMapper} (same package as the entity, so it can
 * call the protected constructor) rather than {@code new MessageEntity()}, keeping the entity's
 * JPA constructor non-public.
 */
@ExtendWith(MockitoExtension.class)
class DispositionServiceTest {

    @Mock MessageRepository messages;
    @Mock AttachmentRepository attachments;
    @Mock DispositionRunRepository runs;
    @Mock DispositionItemRepository items;
    @Mock HoldCheckClient holdCheck;
    @Mock ArchiveKafkaPublisher publisher;
    @Mock AuditEvents audit;

    private final MessageMapper mapper = new MessageMapper(new ObjectMapper());

    // Minutes-scale retention so the cutoffs are in the recent past for the demo (FR-5.1).
    private final RetentionProperties retention = new RetentionProperties(Duration.ofMinutes(2),
            Map.of(MessageType.EMAIL, Duration.ofMinutes(2), MessageType.CHAT, Duration.ofMinutes(1)));

    private DispositionService service;

    @BeforeEach
    void setUp() {
        service = new DispositionService(messages, attachments, runs, items, retention, holdCheck, publisher, audit);
    }

    @Test
    void deletesPastRetentionWhenNotHeld() {
        MessageEntity old = pastRetention("EXCH-1", false);
        when(messages.findDispositionEligible(any(), any(), any(), any())).thenReturn(List.of(old));
        when(holdCheck.isHeld(old.getMessageId())).thenReturn(false);

        DispositionRunEntity run = service.runOnce();

        verify(attachments).deleteByMessageId(old.getMessageId());
        verify(messages).delete(old);
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isEqualTo(1);
        assertThat(run.getSkippedHoldCount()).isZero();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DispositionItemEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(items).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getOutcome()).isEqualTo(DispositionOutcome.DELETED);
        verify(publisher).publishAudit(any());
    }

    @Test
    void skipsMessageThatP4SaysIsHeld() {
        MessageEntity old = pastRetention("EXCH-2", false);
        when(messages.findDispositionEligible(any(), any(), any(), any())).thenReturn(List.of(old));
        when(holdCheck.isHeld(old.getMessageId())).thenReturn(true);

        DispositionRunEntity run = service.runOnce();

        verify(messages, never()).delete(any(MessageEntity.class));
        verify(attachments, never()).deleteByMessageId(anyString());
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
    }

    @Test
    void failsClosedWhenP4IsUnreachable() {
        MessageEntity old = pastRetention("EXCH-3", false);
        when(messages.findDispositionEligible(any(), any(), any(), any())).thenReturn(List.of(old));
        // HoldCheckClient returns true on a communication failure; DispositionService must honour it.
        when(holdCheck.isHeld(old.getMessageId())).thenReturn(true);

        DispositionRunEntity run = service.runOnce();

        verify(messages, never()).delete(any(MessageEntity.class));
        assertThat(run.getSkippedHoldCount()).isEqualTo(1);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
    }

    @Test
    void skipsMessageWhoseLocalHoldFlagIsSetWithoutCallingP4() {
        MessageEntity held = pastRetention("EXCH-4", true);
        when(messages.findDispositionEligible(any(), any(), any(), any())).thenReturn(List.of(held));

        service.runOnce();

        // Local flag short-circuits before the network call.
        verify(holdCheck, never()).isHeld(anyString());
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void emptyEligibleSetCompletesAsANoop() {
        when(messages.findDispositionEligible(any(), any(), any(), any())).thenReturn(List.of());

        DispositionRunEntity run = service.runOnce();

        assertThat(run.getStatus()).isEqualTo(DispositionStatus.COMPLETED);
        assertThat(run.getDeletedCount()).isZero();
        assertThat(run.getSkippedHoldCount()).isZero();
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    /** Builds an email message sent 10 minutes ago — past the 2-minute email retention. */
    private MessageEntity pastRetention(String externalId, boolean onHold) {
        Message wire = new Message(
                null, externalId, "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", List.of("to@x.com"), List.of(), "subj", "body",
                Instant.now().minus(Duration.ofMinutes(10)), "thread-1", null,
                List.of(), List.of());
        MessageEntity entity = mapper.toEntity(wire);
        entity.setOnHold(onHold);
        entity.setHoldCount(onHold ? 1 : 0);
        return entity;
    }
}
