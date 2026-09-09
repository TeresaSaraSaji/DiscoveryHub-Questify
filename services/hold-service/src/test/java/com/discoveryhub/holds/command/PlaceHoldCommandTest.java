package com.discoveryhub.holds.command;

import com.discoveryhub.holds.domain.HoldCoverageEntity;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.messaging.HoldAuditEvents;
import com.discoveryhub.holds.messaging.HoldEventFactory;
import com.discoveryhub.holds.messaging.HoldKafkaPublisher;
import com.discoveryhub.holds.repository.HoldCoverageRepository;
import com.discoveryhub.holds.repository.HoldRepository;
import com.discoveryhub.holds.scope.EmptyScopeException;
import com.discoveryhub.holds.scope.HoldScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The placement command must persist coverage, flip the hold ACTIVE, and publish a per-message
 * hold event for each covered message — and on failure mark the hold FAILED without persisting
 * empty coverage (the unsafe direction).
 */
@ExtendWith(MockitoExtension.class)
class PlaceHoldCommandTest {

    @Mock HoldScopeResolver resolver;
    @Mock HoldRepository holds;
    @Mock HoldCoverageRepository coverage;
    @Mock HoldKafkaPublisher publisher;

    private HoldEventFactory eventFactory = new HoldEventFactory();
    private HoldAuditEvents audit = new HoldAuditEvents();

    private HoldEntity hold;

    @BeforeEach
    void setUp() {
        hold = new HoldEntity("hold-1", "case-1", HoldStatus.RESOLVING, Instant.now());
        hold.applyScope(new HoldScope(List.of("cust-1"), null, null, null));
    }

    private PlaceHoldCommand command() {
        return new PlaceHoldCommand(hold, "corr-1", resolver, holds, coverage,
                publisher, eventFactory, audit);
    }

    @Test
    void resolvesPersistsCoverageFlipsActiveAndPublishesEvents() {
        when(resolver.resolve("hold-1", hold.toScope())).thenReturn(List.of("m1", "m2", "m3"));

        command().execute();

        ArgumentCaptor<java.util.List<HoldCoverageEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(coverage).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue()).extracting(HoldCoverageEntity::getMessageId)
                .containsExactly("m1", "m2", "m3");

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getMessageCount()).isEqualTo(3);
        assertThat(hold.getResolvedAt()).isNotNull();

        // one hold event per message + one audit
        verify(publisher, org.mockito.Mockito.times(3)).publishHoldEvent(any());
        verify(publisher).publishAudit(any());
    }

    @Test
    void emptyScopeMarksHoldFailedAndPublishesFailureAudit() {
        when(resolver.resolve("hold-1", hold.toScope())).thenThrow(new EmptyScopeException("hold-1"));

        command().execute();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.FAILED);
        assertThat(hold.getError()).contains("zero messages");
        verify(coverage, never()).saveAll(any());
        verify(publisher, never()).publishHoldEvent(any());
        verify(publisher).publishAudit(any());
    }

    @Test
    void resolverFailureMarksHoldFailed() {
        when(resolver.resolve("hold-1", hold.toScope())).thenThrow(new RuntimeException("P2 unreachable"));

        command().execute();

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.FAILED);
        verify(coverage, never()).saveAll(any());
    }
}
