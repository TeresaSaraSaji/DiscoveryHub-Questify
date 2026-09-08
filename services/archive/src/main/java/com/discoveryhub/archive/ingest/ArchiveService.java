package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.domain.AttachmentEntity;
import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.AttachmentRepository;
import com.discoveryhub.archive.repository.MessageRepository;
import com.discoveryhub.contracts.Attachment;
import com.discoveryhub.contracts.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores one ingested message durably. The idempotency guarantee (FR-1.6) rests on the
 * {@code UNIQUE (external_id)} constraint in {@code V2__messages.sql}: the {@code existsByExternalId}
 * check is the fast path, and a duplicate that races past it is caught by the constraint and turned
 * into a dedupe outcome by the listener — never an error.
 *
 * <p>Transactional on the database side only. Kafka publication happens after this method returns,
 * so a rolled-back transaction never publishes a {@code messages.archived} for a message that isn't
 * actually stored.
 */
@Service
public class ArchiveService {

    private final MessageRepository messages;
    private final AttachmentRepository attachments;
    private final MessageMapper mapper;

    public ArchiveService(MessageRepository messages, AttachmentRepository attachments, MessageMapper mapper) {
        this.messages = messages;
        this.attachments = attachments;
        this.mapper = mapper;
    }

    @Transactional
    public IngestionResult ingest(Message message) {
        if (messages.existsByExternalId(message.externalId())) {
            return IngestionResult.deduped(message.externalId());
        }
        MessageEntity entity = mapper.toEntity(message);
        List<AttachmentEntity> savedAttachments = new ArrayList<>();
        int ordinal = 0;
        for (Attachment attachment : message.attachments()) {
            savedAttachments.add(mapper.toEntity(attachment, entity.getMessageId(), ordinal++));
        }
        messages.save(entity);
        if (!savedAttachments.isEmpty()) {
            attachments.saveAll(savedAttachments);
        }
        return IngestionResult.stored(entity, savedAttachments);
    }
}
