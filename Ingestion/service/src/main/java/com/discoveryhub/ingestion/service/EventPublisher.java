package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Message;

public interface EventPublisher {

    void publishIngested(Message message);

    void publishAudit(AuditEvent event);
}
