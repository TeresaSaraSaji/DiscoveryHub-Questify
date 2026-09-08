package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.AuditEvent;
import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publishIngested(Message message) {
        // Keyed by messageId so every copy of a message lands on one partition and P2 sees a
        // stable order for it. Blocking on the ack is deliberate: the caller must not be told
        // "accepted" until the broker owns the message, or an outage would silently drop it.
        kafka.send(Topics.MESSAGES_INGESTED, message.messageId(), message).join();
    }

    @Override
    public void publishAudit(AuditEvent event) {
        kafka.send(Topics.AUDIT_EVENTS, event.subjectId(), event);
    }
}
