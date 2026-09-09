package com.discoveryhub.holds.messaging;

import com.discoveryhub.contracts.HoldEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Factory (creational) for {@link HoldEvent}. One place decides the wire shape of a placed/released
 * hold event, so the service says "placed" and the factory builds the event with the right fields
 * and timestamp. Message-scoped events carry {@code messageId} (P2 updates one row); a future
 * custodian-scoped event would carry {@code custodianId} instead (P2 updates every row in a
 * mailbox) — both go through here.
 *
 * <p>The {@code caseId} is always set so P2's audit and the UI can correlate a hold event back to
 * its case, and {@code correlationId} ties every event from one placement/release to one user
 * action across services.
 */
@Component
public final class HoldEventFactory {

    public HoldEvent placed(String messageId, String caseId, String correlationId) {
        return new HoldEvent(messageId, null, true, caseId, correlationId, Instant.now());
    }

    public HoldEvent released(String messageId, String caseId, String correlationId) {
        return new HoldEvent(messageId, null, false, caseId, correlationId, Instant.now());
    }

    public HoldEvent custodianPlaced(String custodianId, String caseId, String correlationId) {
        return new HoldEvent(null, custodianId, true, caseId, correlationId, Instant.now());
    }

    public HoldEvent custodianReleased(String custodianId, String caseId, String correlationId) {
        return new HoldEvent(null, custodianId, false, caseId, correlationId, Instant.now());
    }
}
