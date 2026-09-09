package com.discoveryhub.holds.command;

import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import com.discoveryhub.holds.scope.HoldScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The factory builds the right {@link HoldCommand} for a {@link HoldCommandMessage} — a
 * {@link PlaceHoldCommand} for PLACE, a {@link ReleaseHoldCommand} for RELEASE — and a
 * {@link ReleaseHoldCommand} for case closure. An unknown type throws so the listener does not
 * run an undefined operation.
 */
@ExtendWith(MockitoExtension.class)
class HoldCommandFactoryTest {

    @Mock HoldScopeResolver resolver;
    @Mock HoldRepository holds;
    @Mock HoldCoverageRepository coverage;
    @Mock HoldKafkaPublisher publisher;

    private HoldCommandFactory factory;

    @BeforeEach
    void setUp() {
        factory = new HoldCommandFactory(resolver, holds, coverage, publisher,
                new HoldEventFactory(), new HoldAuditEvents());
    }

    private HoldEntity hold(String id) {
        HoldEntity h = new HoldEntity(id, "case-1", HoldStatus.RESOLVING, Instant.now());
        h.applyScope(new HoldScope(List.of("cust-1"), null, null, null));
        return h;
    }

    @Test
    void forMessagePlaceReturnsPlaceHoldCommand() {
        HoldEntity h = hold("hold-1");
        HoldCommand cmd = factory.forMessage(
                new HoldCommandMessage("hold-1", HoldCommandMessage.TYPE_PLACE, "corr-1"), h);

        assertThat(cmd).isInstanceOf(PlaceHoldCommand.class);
        assertThat(cmd.holdId()).isEqualTo("hold-1");
        assertThat(cmd.type()).isEqualTo(HoldCommandMessage.TYPE_PLACE);
    }

    @Test
    void forMessageReleaseReturnsReleaseHoldCommand() {
        HoldEntity h = hold("hold-1");
        h.setStatus(HoldStatus.ACTIVE);
        HoldCommand cmd = factory.forMessage(
                new HoldCommandMessage("hold-1", HoldCommandMessage.TYPE_RELEASE, "corr-1"), h);

        assertThat(cmd).isInstanceOf(ReleaseHoldCommand.class);
        assertThat(cmd.type()).isEqualTo(HoldCommandMessage.TYPE_RELEASE);
    }

    @Test
    void releaseForCaseClosureReturnsReleaseCommandWithCaseClosedReason() {
        HoldEntity h = hold("hold-1");
        HoldCommand cmd = factory.releaseForCaseClosure(h, "corr-1");

        assertThat(cmd).isInstanceOf(ReleaseHoldCommand.class);
        assertThat(cmd.holdId()).isEqualTo("hold-1");
    }

    @Test
    void forMessageUnknownTypeThrows() {
        HoldEntity h = hold("hold-1");

        assertThatThrownBy(() -> factory.forMessage(
                new HoldCommandMessage("hold-1", "UNKNOWN", "corr-1"), h))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown hold command type");
    }
}
