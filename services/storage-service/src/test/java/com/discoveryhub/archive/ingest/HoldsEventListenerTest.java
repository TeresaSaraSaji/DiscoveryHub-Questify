package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.archive.messaging.ArchiveKafkaPublisher;
import com.discoveryhub.archive.messaging.AuditEvents;
import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import com.discoveryhub.contracts.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 mirrors P4's hold state so the disposition candidate query can skip held rows without a
 * per-message call. The contract between the two is that {@code held=false} means "nothing holds
 * this message any more" — P4 owns the coverage table and withholds the event while any other
 * hold still covers the message (FR-4.5), so this listener applies the event as state.
 *
 * <p>The tests that matter here are the overlapping ones. Under the old decrementing counter, a
 * message that had been held twice and then released twice ended at {@code hold_count = 1} once
 * P4 stopped sending the redundant first release — permanently {@code on_hold}, permanently
 * exempt from retention. The other direction is worse and is covered too: nothing may clear the
 * flag while P4 still considers the message held.
 */
@ExtendWith(MockitoExtension.class)
class HoldsEventListenerTest {

    @Mock MessageHoldStatusRepository messages;
    @Mock ArchiveKafkaPublisher publisher;

    private final ObjectMapper json = new ObjectMapper();
    private HoldsEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new HoldsEventListener(messages, json, publisher, new AuditEvents());
    }

    private MessageHoldStatus row(String messageId) {
        return new MessageHoldStatus(messageId, "EXCH-" + messageId, "custodian-1",
                MessageType.EMAIL, Instant.parse("2024-05-11T21:37:00Z"), Instant.now(), null);
    }

    private String event(String messageId, boolean held) {
        return """
                {"messageId":"%s","held":%s,"caseId":"case-1","correlationId":"corr-1",
                 "occurredAt":"2024-05-11T21:37:00Z"}
                """.formatted(messageId, held);
    }

    private String custodianEvent(String custodianId, boolean held) {
        return """
                {"custodianId":"%s","held":%s,"caseId":"case-1","correlationId":"corr-1",
                 "occurredAt":"2024-05-11T21:37:00Z"}
                """.formatted(custodianId, held);
    }

    @Test
    void placingAHoldFlagsTheRow() {
        MessageHoldStatus m = row("m123");
        when(messages.findById("m123")).thenReturn(Optional.of(m));

        listener.onHoldEvent(event("m123", true));

        assertThat(m.isOnHold()).isTrue();
        assertThat(m.getHoldCount()).isEqualTo(1);
        verify(messages).save(m);
    }

    @Test
    void twoOverlappingHoldsCountTwiceAndTheRowStaysFlagged() {
        MessageHoldStatus m = row("m123");
        when(messages.findById("m123")).thenReturn(Optional.of(m));

        listener.onHoldEvent(event("m123", true));   // hold A — Rahul's emails
        listener.onHoldEvent(event("m123", true));   // hold B — Phoenix investigation

        assertThat(m.getHoldCount()).isEqualTo(2);
        assertThat(m.isOnHold()).isTrue();
    }

    @Test
    void theSingleReleaseP4SendsForTwoOverlappingHoldsClearsTheFlag() {
        // Releasing hold A produces no event at all for m123 (P4 knows B still covers it), so the
        // one release event P2 ever sees for this message is the one for the last hold standing.
        // A decrementing mirror would land on hold_count = 1 here and never unflag the row.
        MessageHoldStatus m = row("m123");
        when(messages.findById("m123")).thenReturn(Optional.of(m));

        listener.onHoldEvent(event("m123", true));
        listener.onHoldEvent(event("m123", true));
        listener.onHoldEvent(event("m123", false));

        assertThat(m.getHoldCount()).isZero();
        assertThat(m.isOnHold()).isFalse();
    }

    @Test
    void aRedeliveredPlaceEventLeavesTheRowHeld() {
        MessageHoldStatus m = row("m123");
        when(messages.findById("m123")).thenReturn(Optional.of(m));

        listener.onHoldEvent(event("m123", true));
        listener.onHoldEvent(event("m123", true));

        assertThat(m.isOnHold()).isTrue();
    }

    @Test
    void aRedeliveredReleaseEventDoesNotDriveTheCountNegative() {
        MessageHoldStatus m = row("m123");
        when(messages.findById("m123")).thenReturn(Optional.of(m));

        listener.onHoldEvent(event("m123", true));
        listener.onHoldEvent(event("m123", false));
        listener.onHoldEvent(event("m123", false));

        assertThat(m.getHoldCount()).isZero();
        assertThat(m.isOnHold()).isFalse();
    }

    @Test
    void aPlaceAfterAReleaseFlagsTheRowAgain() {
        MessageHoldStatus m = row("m123");
        when(messages.findById("m123")).thenReturn(Optional.of(m));

        listener.onHoldEvent(event("m123", true));
        listener.onHoldEvent(event("m123", false));
        listener.onHoldEvent(event("m123", true));

        assertThat(m.isOnHold()).isTrue();
        assertThat(m.getHoldCount()).isEqualTo(1);
    }

    @Test
    void aCustodianScopedEventAppliesToEveryRowInTheMailbox() {
        MessageHoldStatus m1 = row("m1");
        MessageHoldStatus m2 = row("m2");
        when(messages.findByCustodianId("custodian-1")).thenReturn(List.of(m1, m2));

        listener.onHoldEvent(custodianEvent("custodian-1", true));

        assertThat(m1.isOnHold()).isTrue();
        assertThat(m2.isOnHold()).isTrue();
    }

    @Test
    void anEventForAnUnknownMessageIsIgnored() {
        when(messages.findById("missing")).thenReturn(Optional.empty());

        listener.onHoldEvent(event("missing", true));

        verify(messages, never()).save(any());
        verify(publisher, never()).publishAudit(any());
    }

    @Test
    void anUnparseablePayloadIsSkippedRatherThanWedgingTheConsumer() {
        listener.onHoldEvent("{not json");

        verify(messages, never()).findById(any());
        verify(messages, never()).save(any());
    }

    @Test
    void anEventScopedToNeitherMessageNorCustodianTouchesNothing() {
        listener.onHoldEvent("""
                {"held":true,"caseId":"case-1","occurredAt":"2024-05-11T21:37:00Z"}
                """);

        verify(messages, never()).save(any());
    }
}
