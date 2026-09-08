package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Message;

import java.util.ArrayList;
import java.util.List;

class RecordingPublisher implements EventPublisher {

    final List<Message> ingested = new ArrayList<>();
    final List<AuditEvent> audit = new ArrayList<>();

    private RuntimeException failWith;

    void failNextPublishes(RuntimeException e) {
        this.failWith = e;
    }

    @Override
    public void publishIngested(Message message) {
        if (failWith != null) {
            throw failWith;
        }
        ingested.add(message);
    }

    @Override
    public void publishAudit(AuditEvent event) {
        audit.add(event);
    }

    List<String> auditActions() {
        return audit.stream().map(AuditEvent::action).toList();
    }
}
