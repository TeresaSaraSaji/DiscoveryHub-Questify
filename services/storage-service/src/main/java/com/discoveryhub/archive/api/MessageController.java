package com.discoveryhub.archive.api;

import com.discoveryhub.archive.domain.ArchivedMessageDocument;
import com.discoveryhub.archive.domain.AttachmentDocument;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.ArchivedMessageRepository;
import com.discoveryhub.archive.retention.MessageDeletionService;
import com.discoveryhub.contracts.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;

/**
 * P2's read + delete API. The frontend fetches message bodies and attachment bytes here (architecture
 * diagram: {@code ui -->|REST: message + attachment fetch| p2}); P5 fetches the same endpoints when
 * building an export package. Returned messages are the archived shape — attachment
 * {@code contentBase64} is dropped, only {@code sha256} is exposed (message-schema.md).
 *
 * <p>The {@code DELETE} endpoint is the demonstrable legal-hold guard (checkpoint 9): a held message
 * is rejected with {@code 409 CONFLICT} rather than deleted. Held messages are also unmodifiable —
 * there is no update endpoint by design, because an archived message is write-once.
 */
@RestController
@RequestMapping("/messages")
public class MessageController {

    private final ArchivedMessageRepository documents;
    private final MessageMapper mapper;
    private final MessageDeletionService deletion;

    public MessageController(ArchivedMessageRepository documents, MessageMapper mapper,
                             MessageDeletionService deletion) {
        this.documents = documents;
        this.mapper = mapper;
        this.deletion = deletion;
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<Message> getMessage(@PathVariable("messageId") String messageId) {
        ArchivedMessageDocument doc = documents.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found: " + messageId));
        return ResponseEntity.ok(mapper.toArchived(doc));
    }

    @GetMapping
    public Page<Message> listMessages(
            @RequestParam(name = "custodianId", required = false) String custodianId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 500), Sort.by(Sort.Direction.DESC, "sentAt"));
        if (custodianId != null && !custodianId.isBlank()) {
            return documents.findByCustodianId(custodianId, pageable).map(mapper::toArchived);
        }
        return documents.findAll(pageable).map(mapper::toArchived);
    }

    /** Raw attachment bytes for download / export packaging. Read straight off the message
     *  document — attachment bytes live there now, there is no separate blob store. */
    @GetMapping("/{messageId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> getAttachmentBytes(@PathVariable("messageId") String messageId,
                                                     @PathVariable("attachmentId") String attachmentId) {
        ArchivedMessageDocument doc = documents.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "message not found: " + messageId));
        AttachmentDocument att = doc.attachments().stream()
                .filter(a -> a.attachmentId().equals(attachmentId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "attachment not found: " + attachmentId));
        byte[] bytes = att.contentBase64() != null ? Base64.getDecoder().decode(att.contentBase64()) : new byte[0];
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        att.contentType() != null ? att.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .header("Content-Disposition", "attachment; filename=\"" + att.filename() + "\"")
                .header("X-Sha256", att.sha256())
                .body(bytes);
    }

    /**
     * Delete one message. The legal-hold guard (checkpoint 9): a held message is rejected with
     * {@code 409 CONFLICT} rather than deleted. The check is the same fail-closed model the
     * disposition job uses — the local {@code on_hold} flag is the fast path, and the P4 hold check
     * is authoritative; if P4 is unreachable the message is treated as held and the delete is
     * refused. Never delete unverified data (FR-4.2).
     *
     * <p>The work itself lives in {@link MessageDeletionService} because it needs a transaction;
     * this method only maps the outcome onto a status code.
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable("messageId") String messageId) {
        return switch (deletion.delete(messageId)) {
            case DELETED -> ResponseEntity.noContent().build();
            case HELD -> throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "message is under legal hold: " + messageId);
            case NOT_FOUND -> throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "message not found: " + messageId);
        };
    }
}
