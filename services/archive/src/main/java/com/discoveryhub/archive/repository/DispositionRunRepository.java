package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.DispositionRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DispositionRunRepository extends JpaRepository<DispositionRunEntity, String> {

    List<DispositionRunEntity> findTop20ByOrderByStartedAtDesc();
}
