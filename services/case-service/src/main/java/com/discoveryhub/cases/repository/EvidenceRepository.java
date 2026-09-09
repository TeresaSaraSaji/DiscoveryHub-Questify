package com.discoveryhub.cases.repository;

import com.discoveryhub.cases.domain.EvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EvidenceRepository extends JpaRepository<EvidenceEntity, Long> {

    List<EvidenceEntity> findByCaseIdOrderByAddedAtAsc(String caseId);

    Optional<EvidenceEntity> findByCaseIdAndMessageId(String caseId, String messageId);

    boolean existsByCaseIdAndMessageId(String caseId, String messageId);

    long countByCaseId(String caseId);

    /** Message ids already on the case — used to de-duplicate a bulk add without N existence checks. */
    @Query("select e.messageId from EvidenceEntity e where e.caseId = :caseId")
    List<String> findMessageIdsByCaseId(@Param("caseId") String caseId);

    void deleteByCaseIdAndMessageId(String caseId, String messageId);
}
