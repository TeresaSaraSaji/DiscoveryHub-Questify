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

    long countByOutcome(DispositionOutcome outcome);
}
