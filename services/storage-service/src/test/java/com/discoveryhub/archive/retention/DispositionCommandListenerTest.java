package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.DeleteReceipt;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2's half of the disposition contract. A delete command is a request from another service, and
 * the whole point of applying it here rather than letting P2.2 write to this database is that this
 * service gets to refuse.
 *
 * <p>So the behaviour that matters is the refusing: a held message survives a delete command, and
 * so does one whose hold status cannot be verified, because {@link HoldCheckClient} answers "held"
 * when P4 is unreachable and this listener must honour that rather than treat it as a clear
 * signal. Both are reported back as {@link DeleteReceipt.Outcome#REFUSED_HOLD} so P2.2's ledger
 * records a refusal instead of an unexplained silence (FR-4.2, FR-4.6, FR-5.3).
 */
@ExtendWith(MockitoExtension.class)
class DispositionCommandListenerTest {

    @Mock MessageRepository messages;
    @Mock HoldCheckClient holdCheck;
    @Mock ArchiveKafkaPublisher publisher;
    @Mock AuditEvents audit;

    private final ObjectMapper json = new ObjectMapper();
    private final MessageMapper mapper = new MessageMapper(new ObjectMapper());

    private DispositionCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new DispositionCommandListener(messages, holdCheck, publisher, audit, json);
    }

    @Test
    void deletesWhenNotHeld() {
        MessageEntity entity = message("EXCH-1", false);
        when(messages.findById("msg-1")).thenReturn(Optional.of(entity));
        when(holdCheck.isHeld("msg-1")).thenReturn(false);

        DeleteReceipt receipt = listener.apply(command("msg-1"));

        verify(messages).delete(entity);
        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.DELETED);
        assertThat(receipt.runId()).isEqualTo("run-1");
        assertThat(receipt.messageId()).isEqualTo("msg-1");
    }

    @Test
    void refusesWhenTheLocalHoldFlagIsSetWithoutAskingP4() {
        MessageEntity entity = message("EXCH-2", true);
        when(messages.findById("msg-1")).thenReturn(Optional.of(entity));

        DeleteReceipt receipt = listener.apply(command("msg-1"));

        verify(messages, never()).delete(any(MessageEntity.class));
        verify(holdCheck, never()).isHeld(anyString());
        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.REFUSED_HOLD);
    }

    @Test
    void refusesWhenP4ReportsAHold() {
        MessageEntity entity = message("EXCH-3", false);
        when(messages.findById("msg-1")).thenReturn(Optional.of(entity));
        when(holdCheck.isHeld("msg-1")).thenReturn(true);

        DeleteReceipt receipt = listener.apply(command("msg-1"));

        verify(messages, never()).delete(any(MessageEntity.class));
        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.REFUSED_HOLD);
    }

    /**
     * The fail-closed case, and the reason this guard is repeated on P2's side at all. P2.2 already
     * cleared this message; if P4 has since become unreachable, "cannot verify" must still mean
     * "do not delete".
     */
    @Test
    void refusesWhenP4CannotBeReached() {
        MessageEntity entity = message("EXCH-4", false);
        when(messages.findById("msg-1")).thenReturn(Optional.of(entity));
        // HoldCheckClient converts an unreachable P4 into `true`; the listener must not second-guess it.
        when(holdCheck.isHeld("msg-1")).thenReturn(true);

        DeleteReceipt receipt = listener.apply(command("msg-1"));

        verify(messages, never()).delete(any(MessageEntity.class));
        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.REFUSED_HOLD);
        verify(audit).dispositionRefused(anyString(), anyString(), anyString());
    }

    /** A replayed command, or one racing another sweep. Not an error, and not a failed delete. */
    @Test
    void reportsNotFoundForAMessageThatIsAlreadyGone() {
        when(messages.findById("msg-1")).thenReturn(Optional.empty());

        DeleteReceipt receipt = listener.apply(command("msg-1"));

        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.NOT_FOUND);
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void reportsFailedWhenTheDeleteItselfThrows() {
        MessageEntity entity = message("EXCH-5", false);
        when(messages.findById("msg-1")).thenReturn(Optional.of(entity));
        when(holdCheck.isHeld("msg-1")).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("connection reset"))
                .when(messages).delete(entity);

        DeleteReceipt receipt = listener.apply(command("msg-1"));

        assertThat(receipt.outcome()).isEqualTo(DeleteReceipt.Outcome.FAILED);
        assertThat(receipt.reason()).contains("connection reset");
    }

    /** The listener parses, then delegates. A payload that is not JSON must not wedge the consumer. */
    @Test
    void skipsAnUnparseablePayloadWithoutPublishingAnything() {
        listener.onDeleteCommand("{not json");

        verify(publisher, never()).publishDeleteReceipt(any());
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void publishesAReceiptForAWellFormedCommand() {
        when(messages.findById("msg-1")).thenReturn(Optional.empty());

        listener.onDeleteCommand(json.writeValueAsString(command("msg-1")));

        ArgumentCaptor<DeleteReceipt> captor = ArgumentCaptor.forClass(DeleteReceipt.class);
        verify(publisher).publishDeleteReceipt(captor.capture());
        assertThat(captor.getValue().messageId()).isEqualTo("msg-1");
        assertThat(captor.getValue().outcome()).isEqualTo(DeleteReceipt.Outcome.NOT_FOUND);
    }

    private DeleteCommand command(String messageId) {
        return new DeleteCommand("run-1", messageId, "EXCH-1", "custodian-1",
                "past retention", Instant.now());
    }

    /**
     * Built through {@link MessageMapper} rather than {@code new MessageEntity()} so the entity's
     * JPA constructor can stay non-public, matching {@code ArchiveServiceTest}.
     */
    private MessageEntity message(String externalId, boolean onHold) {
        Message wire = new Message(
                "msg-1", externalId, "EXCHANGE", MessageType.EMAIL, "custodian-1",
                "from@x.com", List.of("to@x.com"), List.of(), "subj", "body",
                Instant.parse("2017-01-01T00:00:00Z"), "thread-1", null,
                List.of(), List.of());
        MessageEntity entity = mapper.toEntity(wire);
        entity.setOnHold(onHold);
        entity.setHoldCount(onHold ? 1 : 0);
        return entity;
    }
}
