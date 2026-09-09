package com.discoveryhub.holds.command;

import com.discoveryhub.contracts.Topics;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * The Invoker in the Command pattern: a Kafka listener on {@code holds.commands} that
 * deserialises a {@link HoldCommandMessage}, loads the hold, asks {@link HoldCommandFactory} for
 * the wired command, and runs it. The listener knows nothing about how a hold is placed or
 * released — that lives in the command — so the trigger is decoupled from the work.
 *
 * <p>Idempotency: {@link PlaceHoldCommand} and {@link ReleaseHoldCommand} each guard on the hold's
 * current status, so a redelivered {@code PLACE} for a hold that is no longer {@code RESOLVING},
 * or a redelivered {@code RELEASE} for a hold that is already {@code RELEASED}, is a no-op — it
 * does not re-resolve scope, re-persist coverage, or re-publish events.
 */
@Component
public class HoldCommandListener {

    private static final Logger log = LoggerFactory.getLogger(HoldCommandListener.class);

    private final HoldRepository holds;
    private final HoldCommandFactory factory;
    private final ObjectMapper json;

    public HoldCommandListener(HoldRepository holds, HoldCommandFactory factory, ObjectMapper json) {
        this.holds = holds;
        this.factory = factory;
        this.json = json;
    }

    @KafkaListener(topics = Topics.HOLDS_COMMANDS, groupId = "p4-holds")
    public void onCommand(String payload) {
        HoldCommandMessage message;
        try {
            message = json.readValue(payload, HoldCommandMessage.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable holds.commands payload: {}", ex.getMessage());
            return;
        }
        Optional<HoldEntity> maybeHold = holds.findById(message.holdId());
        if (maybeHold.isEmpty()) {
            log.warn("hold command {} for unknown hold {}, skipping", message.type(), message.holdId());
            return;
        }
        try {
            factory.forMessage(message, maybeHold.get()).execute();
        } catch (Exception ex) {
            // A command that throws does not wedge the consumer: the next command on the topic is
            // still processed. This does not guarantee the hold's own status reflects the failure —
            // if the exception came from persisting the status change itself (e.g. a DB error in
            // holds.save), the in-memory entity was mutated but the row was not, so the hold can be
            // left in its prior status with no record of this attempt beyond this log line.
            log.error("hold command {} for hold {} threw: {}", message.type(), message.holdId(), ex.toString(), ex);
        }
    }
}
