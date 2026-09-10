package com.discoveryhub.ingestion.service;

import com.discoveryhub.ingestion.domain.MessageIdMapping;
import com.discoveryhub.ingestion.repository.MessageIdMappingRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * {@link MessageIdMappingStore} backed by P1's own {@code message_id_map} Postgres table.
 */
@Service
public class MessageIdMappingService implements MessageIdMappingStore {

    private final MessageIdMappingRepository repository;
    private final Clock clock;

    public MessageIdMappingService(MessageIdMappingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records the (externalId, messageId) pair. Returns {@code true} if this call created the
     * row, {@code false} if {@code externalId} was already mapped (a duplicate submission caught
     * by the primary key rather than an error).
     */
    @Override
    public boolean claim(String externalId, String messageId) {
        try {
            // Flush immediately rather than letting the insert ride along with the surrounding
            // transaction: the whole point is to find out now whether externalId is already
            // taken, not at some later, unrelated flush point.
            repository.saveAndFlush(new MessageIdMapping(externalId, messageId, clock.instant()));
            return true;
        } catch (DataIntegrityViolationException ex) {
            return false;
        }
    }

    /**
     * Undoes a {@link #claim}. Used only when publishing fails after the claim succeeded — the
     * message was never actually ingested, so a legitimate retry with the same externalId must
     * not be told it is a duplicate of nothing.
     */
    @Override
    public void release(String externalId) {
        repository.deleteById(externalId);
    }
}
