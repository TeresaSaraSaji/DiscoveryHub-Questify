package com.discoveryhub.disposition.repository;

import com.discoveryhub.disposition.domain.DispositionItemEntity;
import com.discoveryhub.disposition.domain.DispositionOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DispositionItemRepository extends JpaRepository<DispositionItemEntity, Long> {

    Page<DispositionItemEntity> findByRunIdOrderByOccurredAtAsc(String runId, Pageable pageable);

    Page<DispositionItemEntity> findByRunIdAndOutcomeOrderByOccurredAtAsc(
            String runId, DispositionOutcome outcome, Pageable pageable);

    /**
     * Every decision ever recorded about one message, across all runs. The question asked when a
     * message is missing from an export, or is present when someone expected it gone.
     */
    List<DispositionItemEntity> findByMessageIdOrderByOccurredAtAsc(String messageId);

    /**
     * Everything a given case's holds have protected from disposition, across every run.
     *
     * <p>The FR-4.6 evidence, per matter: "show that the hold on this case stopped these messages
     * from being destroyed". Survives the hold being released and the case being closed.
     */
    Page<DispositionItemEntity> findByBlockingCaseIdOrderByOccurredAtDesc(String caseId, Pageable pageable);

    long countByOutcome(DispositionOutcome outcome);

    long countByBlockingCaseId(String caseId);
}
