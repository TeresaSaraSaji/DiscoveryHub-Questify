package com.discoveryhub.disposition.repository;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.domain.RetentionPolicyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetentionPolicyRepository extends JpaRepository<RetentionPolicyEntity, MessageType> {
}
