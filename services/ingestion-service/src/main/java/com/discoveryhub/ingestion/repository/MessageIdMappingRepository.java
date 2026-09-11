package com.discoveryhub.ingestion.repository;

import com.discoveryhub.ingestion.domain.MessageIdMapping;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageIdMappingRepository extends JpaRepository<MessageIdMapping, String> {

    boolean existsByExternalId(String externalId);
}
