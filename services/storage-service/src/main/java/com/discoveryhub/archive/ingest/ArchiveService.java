package com.discoveryhub.archive.ingest;

import com.discoveryhub.archive.domain.ArchivedMessageDocument;
import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.archive.domain.MessageMapper;
import com.discoveryhub.archive.repository.ArchivedMessageRepository;
import com.discoveryhub.archive.repository.MessageHoldStatusRepository;
import com.discoveryhub.contracts.Message;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Stores one ingested message durably. Content (including attachment bytes) goes to Mongo as one
 * document; a slim bookkeeping row goes to P2's own Postgres for legal-hold state and the
 * retention/disposition sweep — see {@link ArchivedMessageDocument} and {@link MessageHoldStatus}.
 *
 * <p>The idempotency guarantee (FR-1.6) is now two-layered: P1's {@code message_id_map} is the
 * first line of defence, and {@code UNIQUE (external_id)} on {@code message_hold_status} is P2's
 * own backstop — the {@code existsByExternalId} check below is the fast path, and a duplicate
 * that races past it is caught by that constraint and turned into a dedupe outcome by the
 * listener, never an error.
 *
 * <p>The Mongo write happens before the Postgres row: {@code save} on a Mongo document keyed by
 * {@code messageId} is an idempotent upsert (same id, same deterministic content), so writing it
 * first and then losing a race on the Postgres constraint leaves nothing incorrect behind — the
 * document is simply already there, byte-for-byte the same as this attempt would have written.
 * The other order risks a hold-status row that claims a message no Mongo document backs.
 */
@Service
public class ArchiveService {

    private final ArchivedMessageRepository documents;
    private final MessageHoldStatusRepository holdStatuses;
    private final MessageMapper mapper;

    public ArchiveService(ArchivedMessageRepository documents, MessageHoldStatusRepository holdStatuses,
                          MessageMapper mapper) {
        this.documents = documents;
        this.holdStatuses = holdStatuses;
        this.mapper = mapper;
    }

    public IngestionResult ingest(Message message) {
        if (holdStatuses.existsByExternalId(message.externalId())) {
            return IngestionResult.deduped(message.externalId());
        }
        ArchivedMessageDocument document = mapper.toDocument(message);
        documents.save(document);

        MessageHoldStatus holdStatus = mapper.toHoldStatus(message);
        try {
            holdStatuses.save(holdStatus);
        } catch (DataIntegrityViolationException ex) {
            // Lost a race on UNIQUE(external_id). The Mongo document above is already this exact
            // content under this exact messageId, so there is nothing to undo — just report the
            // dedupe like any other race caught by the constraint.
            return IngestionResult.deduped(message.externalId());
        }
        return IngestionResult.stored(document, holdStatus);
    }
}
