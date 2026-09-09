package com.discoveryhub.cases.repository;

import com.discoveryhub.cases.domain.EvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EvidenceRepository extends JpaRepository<EvidenceEntity, Long> {

    List<EvidenceEntity> findByCaseIdOrderByAddedAtAsc(String caseId);

    /**
     * Every evidence row for any of these messages, across every case — the input to P4's
     * {@code POST /holds/evidence-check} guard for disposition (DISPOSITION.md). Not filtered by
     * hold status here: the caller (hold-service) is the one that knows which cases are under an
     * active hold, so it does that filtering with its own data rather than this service reaching
     * across the network to ask.
     */
    List<EvidenceEntity> findByMessageIdIn(Collection<String> messageIds);

    Optional<EvidenceEntity> findByCaseIdAndMessageId(String caseId, String messageId);

    boolean existsByCaseIdAndMessageId(String caseId, String messageId);

    long countByCaseId(String caseId);

    /** Message ids already on the case — used to de-duplicate a bulk add without N existence checks. */
    @Query("select e.messageId from EvidenceEntity e where e.caseId = :caseId")
    List<String> findMessageIdsByCaseId(@Param("caseId") String caseId);

    void deleteByCaseIdAndMessageId(String caseId, String messageId);
}
