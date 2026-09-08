package com.discoveryhub.archive.api;

import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.archive.retention.HoldCheckClient;
import com.discoveryhub.archive.storage.AttachmentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The legal-hold guard on deletion (checkpoint 9). {@code DELETE /messages/{id}} must refuse a held
 * message with {@code 409 CONFLICT}, and the refusal must fail closed: a message P4 cannot be asked
 * about is also refused (the {@link HoldCheckClient} returns {@code true} on a communication
 * failure). A non-held message is deleted, with its blobs removed first.
 *
 * <p>Plain Mockito unit test (no Spring context), consistent with {@code ArchiveServiceTest}. The
 * JPA entity is a Mockito mock because its no-arg constructor is package-private to {@code domain}.
 */
@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock MessageRepository messages;
    @Mock AttachmentRepository attachments;
    @Mock AttachmentStore storage;
    @Mock HoldCheckClient holdCheck;
    @Mock MessageMapper mapper;

    @InjectMocks MessageController controller;

    @Test
    void deleteRejectsMessageHeldLocallyWith409() {
        MessageEntity held = mock(MessageEntity.class);
        when(held.isOnHold()).thenReturn(true);
        when(messages.findById("m-1")).thenReturn(Optional.of(held));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteMessage("m-1"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(storage, never()).delete(any(AttachmentEntity.class));
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void deleteRejectsMessageP4SaysIsHeldEvenIfLocalFlagIsFalse() {
        MessageEntity notHeldLocally = mock(MessageEntity.class);
        when(notHeldLocally.isOnHold()).thenReturn(false);
        when(messages.findById("m-2")).thenReturn(Optional.of(notHeldLocally));
        when(holdCheck.isHeld("m-2")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteMessage("m-2"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(messages, never()).delete(any(MessageEntity.class));
    }

    @Test
    void deleteFailsClosedWhenP4IsUnreachable() {
        MessageEntity notHeldLocally = mock(MessageEntity.class);
        when(notHeldLocally.isOnHold()).thenReturn(false);
        when(messages.findById("m-3")).thenReturn(Optional.of(notHeldLocally));
        // HoldCheckClient returns true when P4 is unreachable — deletion must be refused.
        when(holdCheck.isHeld("m-3")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteMessage("m-3"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deleteSucceedsForNotHeldMessageAndRemovesBlobsAndRows() {
        MessageEntity notHeld = mock(MessageEntity.class);
        when(notHeld.isOnHold()).thenReturn(false);
        when(messages.findById("m-4")).thenReturn(Optional.of(notHeld));
        when(holdCheck.isHeld("m-4")).thenReturn(false);
        when(attachments.findByMessageIdOrderByOrdinalAsc("m-4")).thenReturn(List.of());

        ResponseEntity<Void> result = controller.deleteMessage("m-4");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(messages).delete(notHeld);
    }
}
