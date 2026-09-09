package com.discoveryhub.holds.command;

import com.discoveryhub.contracts.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes a {@link HoldCommandMessage} to {@code holds.commands} so a worker resolves the hold
 * off the request thread (FR-4.3, NFR-3). The message is keyed by {@code holdId} so all commands
 * for one hold land on one partition and are processed in order — a release that follows a place
 * for the same hold is never overtaken by it.
 *
 * <p>Fire-and-forget with a failure log, matching the rest of the codebase: a failed send is
 * recoverable because the hold is {@code RESOLVING} in the database and the user can see and
 * retry it; blocking the {@code POST /holds} response on a broker hiccup would defeat the async
 * design.
 */
@Component
public class HoldCommandPublisher {

    private static final Logger log = LoggerFactory.getLogger(HoldCommandPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public HoldCommandPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    public void publish(HoldCommandMessage message) {
        try {
            kafka.send(Topics.HOLDS_COMMANDS, message.holdId(), json.writeValueAsString(message))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("failed to publish hold command {} {}: {}",
                                    message.type(), message.holdId(), ex.toString());
                        }
                    });
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialise hold command: " + message, ex);
        }
    }
}
