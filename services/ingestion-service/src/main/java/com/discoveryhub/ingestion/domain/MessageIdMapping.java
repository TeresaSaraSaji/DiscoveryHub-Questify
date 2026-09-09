package com.discoveryhub.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * One row per ingested message: the id the source system sent it with, and the id P1 derived for
 * it. {@code externalId} is the primary key, so a re-submitted file/message hits the
 * {@code pk_message_id_map} constraint rather than inserting a second row — see
 * {@code V1__message_id_map.sql}.
 *
 * <p>Implements {@link Persistable} and always reports {@code isNew() == true}: the id is
 * assigned by us, not generated, so without this Spring Data JPA would treat a non-null id as
 * "already exists" and issue a {@code merge} (a silent upsert) instead of a plain {@code insert}
 * — which would defeat the whole point of the primary key, since a re-submission would just
 * overwrite the row instead of hitting the constraint.
 */
@Entity
@Table(name = "message_id_map")
public class MessageIdMapping implements Persistable<String> {

    @Id
    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "message_id", nullable = false, length = 36)
    private String messageId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MessageIdMapping() {
        // JPA
    }

    public MessageIdMapping(String externalId, String messageId, Instant createdAt) {
        this.externalId = externalId;
        this.messageId = messageId;
        this.createdAt = createdAt;
    }

    public String getExternalId() { return externalId; }
    public String getMessageId() { return messageId; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    @Transient
    public String getId() { return externalId; }

    @Override
    @Transient
    public boolean isNew() { return true; }
}
