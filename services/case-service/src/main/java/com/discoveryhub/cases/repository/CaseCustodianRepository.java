package com.discoveryhub.cases.repository;

import com.discoveryhub.cases.domain.CaseCustodianEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CaseCustodianRepository extends JpaRepository<CaseCustodianEntity, Long> {

    List<CaseCustodianEntity> findByCaseIdOrderByAddedAtAsc(String caseId);

    java.util.Optional<CaseCustodianEntity> findByCaseIdAndCustodianId(String caseId, String custodianId);

    boolean existsByCaseIdAndCustodianId(String caseId, String custodianId);

    /** Just the custodian ids for a case — used to scope a hold without hydrating the rows. */
    @Query("select c.custodianId from CaseCustodianEntity c where c.caseId = :caseId order by c.custodianId")
    List<String> findCustodianIdsByCaseId(@Param("caseId") String caseId);

    void deleteByCaseIdAndCustodianId(String caseId, String custodianId);
}
