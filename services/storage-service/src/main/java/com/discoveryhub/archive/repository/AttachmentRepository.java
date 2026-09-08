package com.discoveryhub.archive.repository;

import com.discoveryhub.archive.domain.AttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttachmentRepository extends JpaRepository<AttachmentEntity, String> {

    List<AttachmentEntity> findByMessageIdOrderByOrdinalAsc(String messageId);

    Optional<AttachmentEntity> findByMessageIdAndAttachmentId(String messageId, String attachmentId);

    void deleteByMessageId(String messageId);
}
