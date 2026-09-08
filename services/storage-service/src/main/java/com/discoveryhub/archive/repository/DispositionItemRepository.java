package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.DispositionItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DispositionItemRepository extends JpaRepository<DispositionItemEntity, Long> {

    List<DispositionItemEntity> findByRunIdOrderByOccurredAtAsc(String runId);
}
