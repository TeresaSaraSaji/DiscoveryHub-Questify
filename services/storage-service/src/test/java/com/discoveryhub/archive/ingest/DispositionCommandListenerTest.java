package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.retention.MessageDeletionService;
import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.DeleteCommand;
import com.discoveryhub.contracts.DeleteReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2's side of {@code KAFKA} delete mode. The important property is that every outcome from
 * {@link MessageDeletionService} — the same guard {@code DELETE /messages/{id}} uses — maps to
 * the right audit trail and the right {@link DeleteReceipt} on {@code disposition.results}, and a
 * replayed command for an already-gone message settles the ledger rather than wedging it at
 * {@code DELETE_REQUESTED}.
 */
@ExtendWith(MockitoExtension.class)
class DispositionCommandListenerTest {

    @Mock MessageDeletionService deletion;
    @Mock ArchiveKafkaPublisher publisher;
    @Mock AuditEvents audit;
    private final ObjectMapper json = new ObjectMapper();

    private DispositionCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new DispositionCommandListener(deletion, publisher, audit, json);
    }

    @Test
    void deletedCommandPublishesADispositionDeletedAuditAndReceipt() {
        when(deletion.delete("m-1")).thenReturn(MessageDeletionService.Outcome.DELETED);
        when(audit.dispositionDeleted("run-1", "m-1", "EXCH-1", "custodian-1"))
                .thenReturn(sampleEvent());

        listener.onDeleteCommand(command("run-1", "m-1", "EXCH-1", "custodian-1"));

        verify(audit).dispositionDeleted("run-1", "m-1", "EXCH-1", "custodian-1");
        verify(publisher).publishAudit(any());
        assertThat(capturedReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.DELETED);
    }

    @Test
    void heldCommandPublishesADispositionRefusedAuditAndReceipt() {
        when(deletion.delete("m-2")).thenReturn(MessageDeletionService.Outcome.HELD);
        when(audit.dispositionRefused("run-2", "m-2", "held")).thenReturn(sampleEvent());

        listener.onDeleteCommand(command("run-2", "m-2", "EXCH-2", "custodian-1"));

        verify(audit).dispositionRefused("run-2", "m-2", "held");
        verify(publisher).publishAudit(any());
        assertThat(capturedReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.REFUSED_HOLD);
    }

    @Test
    void alreadyDeletedCommandSettlesAsNotFoundWithoutAnAudit() {
        when(deletion.delete("m-3")).thenReturn(MessageDeletionService.Outcome.NOT_FOUND);

        listener.onDeleteCommand(command("run-3", "m-3", "EXCH-3", "custodian-1"));

        verify(publisher, never()).publishAudit(any());
        assertThat(capturedReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.NOT_FOUND);
    }

    @Test
    void aDeletionFailurePublishesAFailedReceiptRatherThanWedgingTheLedger() {
        when(deletion.delete("m-4")).thenThrow(new RuntimeException("boom"));

        listener.onDeleteCommand(command("run-4", "m-4", "EXCH-4", "custodian-1"));

        verify(publisher, never()).publishAudit(any());
        assertThat(capturedReceipt().outcome()).isEqualTo(DeleteReceipt.Outcome.FAILED);
    }

    @Test
    void unparseablePayloadIsSkippedRatherThanWedgingTheConsumer() {
        listener.onDeleteCommand("not json");

        verify(deletion, never()).delete(any());
        verify(publisher, never()).publishAudit(any());
        verify(publisher, never()).publishDeleteReceipt(any());
    }

    private DeleteReceipt capturedReceipt() {
        ArgumentCaptor<DeleteReceipt> captor = ArgumentCaptor.forClass(DeleteReceipt.class);
        verify(publisher).publishDeleteReceipt(captor.capture());
        return captor.getValue();
    }

    private String command(String runId, String messageId, String externalId, String custodianId) {
        try {
            return json.writeValueAsString(new DeleteCommand(
                    runId, messageId, externalId, custodianId, "past retention", Instant.now()));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static AuditEvent sampleEvent() {
        return new AuditEvent("event-1", Instant.now(), "P2", "disposition.deleted",
                AuditEvent.Outcome.SUCCESS, "message", "m-1", "system", "run-1", java.util.Map.of());
    }
}
