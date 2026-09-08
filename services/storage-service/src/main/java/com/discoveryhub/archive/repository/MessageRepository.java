package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.MessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {

    Optional<MessageEntity> findByExternalId(String externalId);

    boolean existsByExternalId(String externalId);

    List<MessageEntity> findByCustodianId(String custodianId);

    Page<MessageEntity> findByCustodianId(String custodianId, Pageable pageable);

    long countByOnHoldTrue();
}
