package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.MessageEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {

    Optional<MessageEntity> findByExternalId(String externalId);

    /**
     * Same lookup as {@link #findById}, but takes a row lock for the rest of the transaction.
     * Deletion callers use this instead of {@code findById} between the hold check and the actual
     * delete, so a concurrent {@code HoldsEventListener} update to {@code onHold} for the same row
     * cannot land in the window between "not held" and "row removed" — the listener's
     * {@code save} blocks until this transaction commits or rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MessageEntity m where m.messageId = :messageId")
    Optional<MessageEntity> findByIdForUpdate(@Param("messageId") String messageId);

    boolean existsByExternalId(String externalId);

    List<MessageEntity> findByCustodianId(String custodianId);

    Page<MessageEntity> findByCustodianId(String custodianId, Pageable pageable);

    /**
     * The on-hold figure on the dashboard (FR-8.2), counted in the database against
     * {@code idx_messages_on_hold} rather than by loading rows to count a boolean.
     */
    long countByOnHoldTrue();

    // No eligibility query here. Finding what is past retention is P2.2's job — it computes the
    // cutoffs from the policy it owns and asks this service to delete through
    // disposition.commands. `findDispositionEligible` existed for P2's own sweep and went with it;
    // leaving it behind would be a standing invitation to write a second one.
}
