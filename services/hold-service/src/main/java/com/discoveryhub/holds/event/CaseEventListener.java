package com.discoveryhub.holds.event;

import com.discoveryhub.contracts.CaseEvent;
import com.discoveryhub.contracts.Topics;
import com.discoveryhub.holds.command.HoldCommand;
import com.discoveryhub.holds.command.HoldCommandFactory;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * Observer (behavioural) of the case-service's lifecycle events. The case-service is the Subject —
 * it publishes {@link CaseEvent}s on {@code cases.events}; this listener is the Observer that
 * reacts to them. The one reaction that matters is {@code case.closed}: a closed case's holds are
 * released (FR-4.5), because the case is read-only (FR-2.4) and the legal hold no longer applies.
 *
 * <p>Case and hold are two deployables, and the close→release coordination is asynchronous by
 * design: the case is sealed from the case-service's perspective the moment it transitions, and
 * its holds follow up once this listener consumes the event. The hold-service does not call the
 * case-service to ask "are you closed?" on a timer; it reacts to the event, which is the lower-
 * coupling choice (the case-service does not know the hold-service exists).
 *
 * <p>Releasing on close uses the {@link HoldCommandFactory#releaseForCaseClosure} command, so the
 * release logic — flip status, publish events, audit — is the same code path as a manual release,
 * just triggered by an event rather than an API call.
 */
@Component
public class CaseEventListener {

    private static final Logger log = LoggerFactory.getLogger(CaseEventListener.class);

    private final HoldRepository holds;
    private final HoldCommandFactory commandFactory;
    private final ObjectMapper json;

    public CaseEventListener(HoldRepository holds, HoldCommandFactory commandFactory, ObjectMapper json) {
        this.holds = holds;
        this.commandFactory = commandFactory;
        this.json = json;
    }

    @KafkaListener(topics = Topics.CASES_EVENTS, groupId = "p4-holds")
    public void onCaseEvent(String payload) {
        CaseEvent event;
        try {
            event = json.readValue(payload, CaseEvent.class);
        } catch (JacksonException ex) {
            log.warn("skipping unparseable cases.events payload: {}", ex.getMessage());
            return;
        }

        if (!"case.closed".equals(event.action())) {
            return; // only close matters to the hold-service
        }

        List<HoldEntity> activeHolds = holds.findByCaseIdAndStatus(event.caseId(), HoldStatus.ACTIVE);
        if (activeHolds.isEmpty()) {
            log.debug("case {} closed; no active holds to release", event.caseId());
            return;
        }

        String correlationId = UUID.randomUUID().toString();
        log.info("case {} closed; releasing {} active hold(s)", event.caseId(), activeHolds.size());
        for (HoldEntity hold : activeHolds) {
            try {
                HoldCommand command = commandFactory.releaseForCaseClosure(hold, correlationId);
                command.execute();
            } catch (Exception ex) {
                // One hold failing to release must not stop the others. The hold keeps its ACTIVE
                // status and the next case.close replay (or a manual release) will try again.
                log.error("failed to release hold {} on case close: {}", hold.getHoldId(), ex.toString(), ex);
            }
        }
    }
}
