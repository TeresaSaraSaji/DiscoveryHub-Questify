package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.MessageEntity;
import com.discoveryhub.contracts.MessageType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {

    Optional<MessageEntity> findByExternalId(String externalId);

    /**
     * Same lookup as {@link #findById}, but takes a row lock for the rest of the transaction.
     * Deletion callers use this instead of {@code findById} between the P4 hold check and the
     * actual delete, so a concurrent {@code HoldsEventListener} update to {@code onHold} for the
     * same row cannot land in the window between "P4 said not held" and "row removed" — the
     * listener's {@code save} blocks until this transaction commits or rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MessageEntity m where m.messageId = :messageId")
    Optional<MessageEntity> findByIdForUpdate(@Param("messageId") String messageId);

    boolean existsByExternalId(String externalId);

    List<MessageEntity> findByCustodianId(String custodianId);

    Page<MessageEntity> findByCustodianId(String custodianId, Pageable pageable);

    /**
     * Messages past their type-specific retention and not currently held — the disposition
     * candidate set. Eligibility is computed here rather than stored, so a retention config change
     * (e.g. minutes for the demo, FR-5.1) takes effect on the next run without a backfill.
     */
    @Query("""
            select m from MessageEntity m
            where m.onHold = false
              and ((m.type = :email and m.sentAt <= :emailCutoff)
                or (m.type = :chat  and m.sentAt <= :chatCutoff))
            """)
    List<MessageEntity> findDispositionEligible(@Param("email") MessageType email,
                                                @Param("chat") MessageType chat,
                                                @Param("emailCutoff") Instant emailCutoff,
                                                @Param("chatCutoff") Instant chatCutoff);
}
