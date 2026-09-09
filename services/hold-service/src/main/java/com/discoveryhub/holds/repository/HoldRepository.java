package com.discoveryhub.holds.repository;

import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HoldRepository extends JpaRepository<HoldEntity, String> {

    List<HoldEntity> findByCaseIdOrderByPlacedAtDesc(String caseId);

    List<HoldEntity> findByCaseIdAndStatus(String caseId, HoldStatus status);

    List<HoldEntity> findByStatus(HoldStatus status);

    long countByStatus(HoldStatus status);
}
