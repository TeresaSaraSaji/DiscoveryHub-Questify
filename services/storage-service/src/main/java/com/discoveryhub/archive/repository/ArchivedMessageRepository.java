package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.ArchivedMessageDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ArchivedMessageRepository extends MongoRepository<ArchivedMessageDocument, String> {

    Page<ArchivedMessageDocument> findByCustodianId(String custodianId, Pageable pageable);
}
