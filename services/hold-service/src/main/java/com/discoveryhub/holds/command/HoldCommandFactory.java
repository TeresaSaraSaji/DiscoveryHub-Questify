package com.discoveryhub.holds.command;

import com.discoveryhub.holds.client.CaseStatusClient;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import com.discoveryhub.holds.scope.HoldScopeResolver;
import org.springframework.stereotype.Component;

/**
 * Factory (creational) that builds a {@link HoldCommand} from a {@link HoldCommandMessage} and the
 * loaded hold. The command's dependencies are wired here, on the consumer side — commands are not
 * serialised to Kafka, so the factory is the join between the lightweight wire message and the
 * fully-wired command object.
 *
 * <p>Centralising construction here means the {@link HoldCommandListener} stays a thin invoker: it
 * loads the hold, asks the factory for the command, and runs it. A new command type is a new
 * {@code case} here and a new class, not a change to the listener.
 */
@Component
public final class HoldCommandFactory {

    private final HoldScopeResolver resolver;
    private final HoldRepository holds;
    private final HoldCoverageRepository coverage;
    private final HoldKafkaPublisher publisher;
    private final HoldEventFactory eventFactory;
    private final HoldAuditEvents audit;
    private final CaseStatusClient caseStatus;

    public HoldCommandFactory(HoldScopeResolver resolver, HoldRepository holds,
                              HoldCoverageRepository coverage, HoldKafkaPublisher publisher,
                              HoldEventFactory eventFactory, HoldAuditEvents audit,
                              CaseStatusClient caseStatus) {
        this.resolver = resolver;
        this.holds = holds;
        this.coverage = coverage;
        this.publisher = publisher;
        this.eventFactory = eventFactory;
        this.audit = audit;
        this.caseStatus = caseStatus;
    }

    public HoldCommand forMessage(HoldCommandMessage message, HoldEntity hold) {
        return switch (message.type()) {
            case HoldCommandMessage.TYPE_PLACE -> new PlaceHoldCommand(
                    hold, message.correlationId(), resolver, holds, coverage, publisher, eventFactory, audit,
                    caseStatus);
            case HoldCommandMessage.TYPE_RELEASE -> new ReleaseHoldCommand(
                    hold, "released by command", message.correlationId(),
                    holds, coverage, publisher, eventFactory, audit);
            default -> throw new IllegalArgumentException("unknown hold command type: " + message.type());
        };
    }

    public HoldCommand releaseForCaseClosure(HoldEntity hold, String correlationId) {
        return new ReleaseHoldCommand(hold, "case closed", correlationId,
                holds, coverage, publisher, eventFactory, audit);
    }
}
