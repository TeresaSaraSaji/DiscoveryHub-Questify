package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.archive.repository.ArchivedMessageRepository;
import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The legal-hold guard on deletion (checkpoint 9). A held message must be refused, and the refusal
 * must fail closed: a message P4 cannot be asked about is also refused ({@link HoldCheckClient}
 * returns {@code true} on a communication failure).
 *
 * <p>The ordering test is the important one: the Postgres row is removed (and flushed) before the
 * Mongo document, so a thrown exception from the Mongo delete rolls the Postgres side back too,
 * rather than leaving a Postgres row that claims a message Mongo no longer has.
 *
 * <p>Plain Mockito, consistent with {@code ArchiveServiceTest}. {@code MessageHoldStatus} is a
 * mock because its no-arg constructor is package-private to {@code domain}.
 */
@ExtendWith(MockitoExtension.class)
class MessageDeletionServiceTest {

    @Mock MessageHoldStatusRepository holdStatuses;
    @Mock ArchivedMessageRepository documents;
    @Mock HoldCheckClient holdCheck;

    @InjectMocks MessageDeletionService service;

    @Test
    void unknownMessageIsNotFoundAndTouchesNothing() {
        when(holdStatuses.findByIdForUpdate("nope")).thenReturn(Optional.empty());

        assertThat(service.delete("nope")).isEqualTo(MessageDeletionService.Outcome.NOT_FOUND);
        verifyNoInteractions(documents);
        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
    }

    @Test
    void refusesAMessageHeldByTheLocalFlagWithoutEvenCallingP4() {
        MessageHoldStatus held = mock(MessageHoldStatus.class);
        when(held.isOnHold()).thenReturn(true);
        when(holdStatuses.findByIdForUpdate("m-1")).thenReturn(Optional.of(held));

        assertThat(service.delete("m-1")).isEqualTo(MessageDeletionService.Outcome.HELD);
        // The local flag is the fast path: no need to ask P4 to know the answer is no.
        verifyNoInteractions(holdCheck);
        verifyNoInteractions(documents);
        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
    }

    @Test
    void refusesAMessageP4SaysIsHeldEvenWhenTheLocalFlagIsClear() {
        MessageHoldStatus notHeldLocally = mock(MessageHoldStatus.class);
        when(notHeldLocally.isOnHold()).thenReturn(false);
        when(holdStatuses.findByIdForUpdate("m-2")).thenReturn(Optional.of(notHeldLocally));
        when(holdCheck.isHeld("m-2")).thenReturn(true);

        assertThat(service.delete("m-2")).isEqualTo(MessageDeletionService.Outcome.HELD);
        verifyNoInteractions(documents);
        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
    }

    @Test
    void failsClosedWhenP4IsUnreachable() {
        MessageHoldStatus notHeldLocally = mock(MessageHoldStatus.class);
        when(notHeldLocally.isOnHold()).thenReturn(false);
        when(holdStatuses.findByIdForUpdate("m-3")).thenReturn(Optional.of(notHeldLocally));
        // HoldCheckClient reports held when it cannot reach P4, so the delete must be refused.
        when(holdCheck.isHeld("m-3")).thenReturn(true);

        assertThat(service.delete("m-3")).isEqualTo(MessageDeletionService.Outcome.HELD);
        verify(holdStatuses, never()).delete(any(MessageHoldStatus.class));
    }

    @Test
    void deletesBothStoresForANotHeldMessage() {
        MessageHoldStatus notHeld = mock(MessageHoldStatus.class);
        when(notHeld.isOnHold()).thenReturn(false);
        when(holdStatuses.findByIdForUpdate("m-4")).thenReturn(Optional.of(notHeld));
        when(holdCheck.isHeld("m-4")).thenReturn(false);

        assertThat(service.delete("m-4")).isEqualTo(MessageDeletionService.Outcome.DELETED);

        verify(holdStatuses).delete(notHeld);
        verify(documents).deleteById("m-4");
    }

    @Test
    void deletesTheHoldStatusRowBeforeTheMongoDocument() {
        MessageHoldStatus notHeld = mock(MessageHoldStatus.class);
        when(notHeld.isOnHold()).thenReturn(false);
        when(holdStatuses.findByIdForUpdate("m-5")).thenReturn(Optional.of(notHeld));
        when(holdCheck.isHeld("m-5")).thenReturn(false);

        service.delete("m-5");

        InOrder order = inOrder(holdStatuses, documents);
        order.verify(holdStatuses).delete(notHeld);
        order.verify(holdStatuses).flush();
        order.verify(documents).deleteById("m-5");
    }
}
