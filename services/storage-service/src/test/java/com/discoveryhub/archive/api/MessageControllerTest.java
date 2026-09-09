package com.discoveryhub.archive.api;

import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.archive.retention.MessageDeletionService;
import com.discoveryhub.archive.storage.AttachmentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * The controller's only job on the delete path is mapping {@link MessageDeletionService.Outcome} to
 * a status code — 409 for a held message is the demonstrable legal-hold guard (checkpoint 9). The
 * guard itself, and the fail-closed behaviour behind it, is tested in
 * {@code MessageDeletionServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock MessageRepository messages;
    @Mock AttachmentRepository attachments;
    @Mock MessageMapper mapper;
    @Mock AttachmentStore storage;
    @Mock MessageDeletionService deletion;

    @InjectMocks MessageController controller;

    @Test
    void heldMessageIsRejectedWith409() {
        when(deletion.delete("m-1")).thenReturn(MessageDeletionService.Outcome.HELD);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteMessage("m-1"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("legal hold");
    }

    @Test
    void unknownMessageIs404() {
        when(deletion.delete("m-2")).thenReturn(MessageDeletionService.Outcome.NOT_FOUND);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.deleteMessage("m-2"));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletedMessageIs204() {
        when(deletion.delete("m-3")).thenReturn(MessageDeletionService.Outcome.DELETED);

        ResponseEntity<Void> result = controller.deleteMessage("m-3");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
