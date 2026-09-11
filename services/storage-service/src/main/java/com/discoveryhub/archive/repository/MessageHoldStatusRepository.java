package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.MessageHoldStatus;
import com.discoveryhub.contracts.MessageType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MessageHoldStatusRepository extends JpaRepository<MessageHoldStatus, String> {

    boolean existsByExternalId(String externalId);

    List<MessageHoldStatus> findByCustodianId(String custodianId);

    long countByOnHoldTrue();

    /**
     * Same lookup as {@link #findById}, but takes a row lock for the rest of the transaction.
     * Deletion callers use this instead of plain {@code findById} between the P4 hold check and
     * the actual delete, so a concurrent {@code HoldsEventListener} update to {@code onHold} for
     * the same row cannot land in the window between "P4 said not held" and "row removed" — the
     * listener's {@code save} blocks until this transaction commits or rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MessageHoldStatus m where m.messageId = :messageId")
    Optional<MessageHoldStatus> findByIdForUpdate(@Param("messageId") String messageId);

    /**
     * Messages past their type-specific retention and not currently held — the disposition
     * candidate set. Eligibility is computed here rather than stored, so a retention config
     * change (e.g. minutes for the demo, FR-5.1) takes effect on the next run without a backfill.
     *
     * <p>A message with {@code retentionOverrideAt} set (P1 tagged it with
     * {@code RetentionLabels.DEMO_RETENTION}) is eligible once that instant has passed,
     * regardless of its type's normal cutoff — and the type-based clauses never apply to it,
     * so a demoed message cannot become eligible early via its type by coincidence.
     */
    @Query("""
            select m from MessageHoldStatus m
            where m.onHold = false
              and (
                (m.retentionOverrideAt is not null and m.retentionOverrideAt <= :now)
                or (m.retentionOverrideAt is null and (
                     (m.type = :email and m.sentAt <= :emailCutoff)
                  or (m.type = :chat  and m.sentAt <= :chatCutoff)))
              )
            """)
    List<MessageHoldStatus> findDispositionEligible(@Param("email") MessageType email,
                                                     @Param("chat") MessageType chat,
                                                     @Param("emailCutoff") Instant emailCutoff,
                                                     @Param("chatCutoff") Instant chatCutoff,
                                                     @Param("now") Instant now);
}
