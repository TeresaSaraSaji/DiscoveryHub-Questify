package com.discoveryhub.holds.repository;

import com.discoveryhub.holds.domain.HoldCoverageEntity;
import com.discoveryhub.holds.domain.HoldCoverageId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HoldCoverageRepository extends JpaRepository<HoldCoverageEntity, HoldCoverageId> {

    List<HoldCoverageEntity> findByHoldId(String holdId);

    long countByHoldId(String holdId);

    /** The hot path (FR-4.2): is this messageId covered by any ACTIVE hold? */
    @Query("""
            select count(c) > 0 from HoldCoverageEntity c
            join HoldEntity h on h.holdId = c.holdId
            where c.messageId = :messageId and h.status = com.discoveryhub.holds.domain.HoldStatus.ACTIVE
            """)
    boolean isCoveredByActiveHold(@Param("messageId") String messageId);

    /** Every active hold id covering a message, for overlapping-hold release counting (FR-4.5). */
    @Query("""
            select c.holdId from HoldCoverageEntity c
            join HoldEntity h on h.holdId = c.holdId
            where c.messageId = :messageId and h.status = com.discoveryhub.holds.domain.HoldStatus.ACTIVE
            """)
    List<String> findActiveHoldIdsByMessageId(@Param("messageId") String messageId);

    /** Message ids covered by a hold — used to publish release events for each on release. */
    @Query("select c.messageId from HoldCoverageEntity c where c.holdId = :holdId")
    List<String> findMessageIdsByHoldId(@Param("holdId") String holdId);

    /**
     * Of the messages this hold covers, the ones no <i>other</i> ACTIVE hold covers — the only
     * ones that actually stop being protected when this hold is released (FR-4.5).
     *
     * <p>A message under hold A ("Rahul's mailbox") and hold B ("Phoenix investigation") is still
     * evidence after A is released; it becomes disposition-eligible only once both are gone.
     *
     * <p>{@code :holdId} is excluded explicitly rather than relying on the releasing hold having
     * already been flipped to {@code RELEASED}: the caller computes this before or after the flip
     * depending on the path, and an unflushed status change would otherwise make the hold appear
     * to keep protecting its own messages, so nothing would ever be reported as unprotected.
     */
    @Query("""
            select c.messageId from HoldCoverageEntity c
            where c.holdId = :holdId
              and not exists (
                select 1 from HoldCoverageEntity o
                join HoldEntity h on h.holdId = o.holdId
                where o.messageId = c.messageId
                  and o.holdId <> :holdId
                  and h.status = com.discoveryhub.holds.domain.HoldStatus.ACTIVE
              )
            """)
    List<String> findMessageIdsUnprotectedByReleasing(@Param("holdId") String holdId);

    /**
     * Distinct held-message count for a case (FR-4.4). Overlapping active holds on the same case
     * (FR-4.5) can cover the same messageId; summing {@link #countByHoldId} per hold double-counts
     * those messages, so this counts distinct message ids across every active hold on the case.
     */
    @Query("""
            select count(distinct c.messageId) from HoldCoverageEntity c
            join HoldEntity h on h.holdId = c.holdId
            where h.caseId = :caseId and h.status = com.discoveryhub.holds.domain.HoldStatus.ACTIVE
            """)
    long countDistinctMessageIdsByCaseIdAndActiveHolds(@Param("caseId") String caseId);
}
