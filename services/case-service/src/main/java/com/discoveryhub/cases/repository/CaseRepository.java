package com.discoveryhub.cases.repository;

import com.discoveryhub.cases.domain.CaseEntity;
import com.discoveryhub.cases.domain.CaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<CaseEntity, String> {

    Page<CaseEntity> findByStatus(CaseStatus status, Pageable pageable);

    long countByStatus(CaseStatus status);
}
