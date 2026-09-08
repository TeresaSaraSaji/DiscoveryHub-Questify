package com.discoveryhub.archive.api;

import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.archive.retention.HoldCheckClient;
import com.discoveryhub.archive.storage.AttachmentStore;
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

import java.util.List;

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

    private final MessageRepository messages;
    private final AttachmentRepository attachments;
    private final MessageMapper mapper;
    private final AttachmentStore storage;
    private final HoldCheckClient holdCheck;

    public MessageController(MessageRepository messages, AttachmentRepository attachments,
                             MessageMapper mapper, AttachmentStore storage, HoldCheckClient holdCheck) {
        this.messages = messages;
        this.attachments = attachments;
        this.mapper = mapper;
        this.storage = storage;
        this.holdCheck = holdCheck;
    }

    @GetMapping("/{messageId}")
    public ResponseEntity<Message> getMessage(@PathVariable("messageId") String messageId) {
        MessageEntity entity = messages.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found: " + messageId));
        List<AttachmentEntity> atts = attachments.findByMessageIdOrderByOrdinalAsc(messageId);
        return ResponseEntity.ok(mapper.toArchived(entity, atts));
    }

    @GetMapping
    public Page<Message> listMessages(
            @RequestParam(name = "custodianId", required = false) String custodianId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 500), Sort.by(Sort.Direction.DESC, "sentAt"));
        if (custodianId != null && !custodianId.isBlank()) {
            return messages.findByCustodianId(custodianId, pageable).map(e ->
                    mapper.toArchived(e, attachments.findByMessageIdOrderByOrdinalAsc(e.getMessageId())));
        }
        return messages.findAll(pageable).map(e ->
                mapper.toArchived(e, attachments.findByMessageIdOrderByOrdinalAsc(e.getMessageId())));
    }

    /** Raw attachment bytes for download / export packaging. Content type from the stored metadata;
     *  the bytes themselves are streamed from local disk (falling back to the S3 offload copy if the
     *  local file is unavailable). */
    @GetMapping("/{messageId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> getAttachmentBytes(@PathVariable("messageId") String messageId,
                                                     @PathVariable("attachmentId") String attachmentId) {
        AttachmentEntity att = attachments.findByMessageIdAndAttachmentId(messageId, attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "attachment not found: " + attachmentId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        att.getContentType() != null ? att.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .header("Content-Disposition", "attachment; filename=\"" + att.getFilename() + "\"")
                .header("X-Sha256", att.getSha256())
                .body(storage.load(att));
    }

    /**
     * Delete one message. The legal-hold guard (checkpoint 9): a held message is rejected with
     * {@code 409 CONFLICT} rather than deleted. The check is the same fail-closed model the
     * disposition job uses — the local {@code on_hold} flag is the fast path, and the
     * {@link HoldCheckClient} call to P4 is the authoritative check; if P4 is unreachable the message
     * is treated as held and the delete is refused. Never delete unverified data (FR-4.2).
     *
     * <p>When allowed, the attachment blobs (local file + optional S3 offload copy) are removed
     * before the metadata rows, mirroring the disposition path.
     */
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> deleteMessage(@PathVariable("messageId") String messageId) {
        MessageEntity entity = messages.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "message not found: " + messageId));
        if (entity.isOnHold() || holdCheck.isHeld(messageId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "message is under legal hold: " + messageId);
        }
        for (AttachmentEntity a : attachments.findByMessageIdOrderByOrdinalAsc(messageId)) {
            storage.delete(a);
        }
        attachments.deleteByMessageId(messageId);
        messages.delete(entity);
        messages.flush();
        return ResponseEntity.noContent().build();
    }
}
