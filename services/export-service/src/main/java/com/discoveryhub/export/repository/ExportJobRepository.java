package com.discoveryhub.export.repository;

import com.discoveryhub.export.domain.ExportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportJobRepository extends JpaRepository<ExportJobEntity, String> {

    List<ExportJobEntity> findTop50ByOrderByQueuedAtDesc();
}
