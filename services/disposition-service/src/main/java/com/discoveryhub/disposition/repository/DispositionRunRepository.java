package com.discoveryhub.disposition.repository;

import com.discoveryhub.disposition.domain.DispositionRunEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispositionRunRepository extends JpaRepository<DispositionRunEntity, String> {

    Page<DispositionRunEntity> findAllByOrderByStartedAtDesc(Pageable pageable);
}
