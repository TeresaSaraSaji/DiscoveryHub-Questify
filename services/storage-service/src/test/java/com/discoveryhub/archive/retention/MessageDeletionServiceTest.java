package com.discoveryhub.archive.retention;

import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.archive.storage.AttachmentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * <p>The ordering test is the important one. Blob deletion is not transactional, so it has to be
 * deferred until after the row removal commits — an earlier version deleted the bytes inline and a
 * rollback then left live rows pointing at files that no longer existed.
 *
 * <p>Plain Mockito, consistent with {@code ArchiveServiceTest}. The JPA entities are mocks because
 * their no-arg constructors are package-private to {@code domain}.
 */
@ExtendWith(MockitoExtension.class)
class MessageDeletionServiceTest {

    @Mock MessageRepository messages;
    @Mock AttachmentRepository attachments;
    @Mock AttachmentStore storage;
    @Mock HoldCheckClient holdCheck;

    @InjectMocks MessageDeletionService service;

    @Test
    void unknownMessageIsNotFoundAndTouchesNothing() {
        when(messages.findByIdForUpdate("nope")).thenReturn(Optional.empty());

        assertThat(service.delete("nope")).isEqualTo(MessageDeletionService.Outcome.NOT_FOUND);
        verifyNoInteractions(storage);
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void refusesAMessageHeldByTheLocalFlagWithoutEvenCallingP4() {
        MessageEntity held = mock(MessageEntity.class);
        when(held.isOnHold()).thenReturn(true);
        when(messages.findByIdForUpdate("m-1")).thenReturn(Optional.of(held));

        assertThat(service.delete("m-1")).isEqualTo(MessageDeletionService.Outcome.HELD);
        // The local flag is the fast path: no need to ask P4 to know the answer is no.
        verifyNoInteractions(holdCheck);
        verifyNoInteractions(storage);
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void refusesAMessageP4SaysIsHeldEvenWhenTheLocalFlagIsClear() {
        MessageEntity notHeldLocally = mock(MessageEntity.class);
        when(notHeldLocally.isOnHold()).thenReturn(false);
        when(messages.findByIdForUpdate("m-2")).thenReturn(Optional.of(notHeldLocally));
        when(holdCheck.isHeld("m-2")).thenReturn(true);

        assertThat(service.delete("m-2")).isEqualTo(MessageDeletionService.Outcome.HELD);
        verifyNoInteractions(storage);
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void failsClosedWhenP4IsUnreachable() {
        MessageEntity notHeldLocally = mock(MessageEntity.class);
        when(notHeldLocally.isOnHold()).thenReturn(false);
        when(messages.findByIdForUpdate("m-3")).thenReturn(Optional.of(notHeldLocally));
        // HoldCheckClient reports held when it cannot reach P4, so the delete must be refused.
        when(holdCheck.isHeld("m-3")).thenReturn(true);

        assertThat(service.delete("m-3")).isEqualTo(MessageDeletionService.Outcome.HELD);
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void deletesRowsAndSchedulesBlobRemovalForANotHeldMessage() {
        MessageEntity notHeld = mock(MessageEntity.class);
        AttachmentEntity att = mock(AttachmentEntity.class);
        when(notHeld.isOnHold()).thenReturn(false);
        when(messages.findByIdForUpdate("m-4")).thenReturn(Optional.of(notHeld));
        when(holdCheck.isHeld("m-4")).thenReturn(false);
        when(attachments.findByMessageIdOrderByOrdinalAsc("m-4")).thenReturn(List.of(att));

        assertThat(service.delete("m-4")).isEqualTo(MessageDeletionService.Outcome.DELETED);

        verify(attachments).deleteByMessageId("m-4");
        verify(messages).delete(notHeld);
        verify(storage).deleteAfterCommit(List.of(att));
    }

    @Test
    void removesTheRowsBeforeSchedulingTheBlobsSoARollbackCannotStrandLiveRows() {
        MessageEntity notHeld = mock(MessageEntity.class);
        AttachmentEntity att = mock(AttachmentEntity.class);
        when(notHeld.isOnHold()).thenReturn(false);
        when(messages.findByIdForUpdate("m-5")).thenReturn(Optional.of(notHeld));
        when(holdCheck.isHeld("m-5")).thenReturn(false);
        when(attachments.findByMessageIdOrderByOrdinalAsc("m-5")).thenReturn(List.of(att));

        service.delete("m-5");

        InOrder order = inOrder(attachments, messages, storage);
        order.verify(attachments).deleteByMessageId("m-5");
        order.verify(messages).delete(notHeld);
        order.verify(storage).deleteAfterCommit(List.of(att));
    }

    @Test
    void neverDeletesBlobsInlineOnAnyPath() {
        MessageEntity notHeld = mock(MessageEntity.class);
        when(notHeld.isOnHold()).thenReturn(false);
        when(messages.findByIdForUpdate("m-6")).thenReturn(Optional.of(notHeld));
        when(holdCheck.isHeld("m-6")).thenReturn(false);
        when(attachments.findByMessageIdOrderByOrdinalAsc("m-6")).thenReturn(List.of());

        service.delete("m-6");

        // An inline delete is the bug this test exists to prevent: it destroys bytes that a
        // rollback would then "restore" rows for.
        verify(storage, never()).delete(any(AttachmentEntity.class));
        verify(storage, never()).deleteBytes(any());
        verify(storage, never()).load(anyString(), anyString());
    }
}
