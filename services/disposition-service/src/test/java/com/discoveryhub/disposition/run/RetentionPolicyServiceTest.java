package com.discoveryhub.disposition.run;

import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.disposition.config.RetentionDefaults;
import com.discoveryhub.disposition.domain.RetentionPolicyEntity;
import com.discoveryhub.disposition.messaging.AuditEvents;
import com.discoveryhub.disposition.messaging.DispositionKafkaPublisher;
import com.discoveryhub.disposition.repository.RetentionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-5.1: retention is configurable per communication type, and settable to minutes for the demo.
 *
 * <p>The behaviour worth protecting is the seeding rule — config fills gaps, it never overwrites —
 * because the alternative silently reverts a demo's two-minute retention to seven years on the
 * next restart, which looks like the sweep being broken.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetentionPolicyServiceTest {

    @Mock RetentionPolicyRepository policies;
    @Mock DispositionKafkaPublisher publisher;

    private final AuditEvents audit = new AuditEvents();

    private final RetentionDefaults defaults = new RetentionDefaults(
            Duration.ofDays(2555),
            Map.of(MessageType.EMAIL, Duration.ofDays(2555), MessageType.CHAT, Duration.ofDays(1095)));

    private RetentionPolicyService service;

    @BeforeEach
    void setUp() {
        when(policies.save(any(RetentionPolicyEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new RetentionPolicyService(policies, defaults, publisher, audit);
    }

    @Test
    void seedsEveryTypeMissingFromTheTable() {
        when(policies.findById(any())).thenReturn(Optional.empty());

        service.seedMissingPolicies();

        verify(policies, org.mockito.Mockito.times(MessageType.values().length))
                .save(any(RetentionPolicyEntity.class));
    }

    @Test
    void doesNotOverwriteAPolicySomeoneAlreadySet() {
        // The demo has set email retention to two minutes. A restart must not undo that.
        RetentionPolicyEntity existing =
                new RetentionPolicyEntity(MessageType.EMAIL, Duration.ofMinutes(2), "investigator");
        when(policies.findById(MessageType.EMAIL)).thenReturn(Optional.of(existing));
        when(policies.findById(MessageType.CHAT)).thenReturn(Optional.empty());

        service.seedMissingPolicies();

        assertThat(existing.period()).isEqualTo(Duration.ofMinutes(2));
        verify(policies, never()).save(existing);
    }

    @Test
    void updatingAPeriodIsAudited() {
        RetentionPolicyEntity existing =
                new RetentionPolicyEntity(MessageType.EMAIL, Duration.ofDays(2555), "seed");
        when(policies.findById(MessageType.EMAIL)).thenReturn(Optional.of(existing));

        service.updatePeriod(MessageType.EMAIL, Duration.ofMinutes(2), "investigator");

        assertThat(existing.period()).isEqualTo(Duration.ofMinutes(2));
        assertThat(existing.getUpdatedBy()).isEqualTo("investigator");
        // Shortening retention is an instruction to destroy data; the chain of custody records it.
        verify(publisher).publishAudit(any());
    }

    @Test
    void rejectsANonPositivePeriod() {
        // Zero retention would make the entire corpus eligible on the next sweep.
        when(policies.findById(MessageType.EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePeriod(MessageType.EMAIL, Duration.ZERO, "investigator"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                service.updatePeriod(MessageType.EMAIL, Duration.ofMinutes(-5), "investigator"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cutoffsAreDerivedFromTheStoredPeriodPerType() {
        when(policies.findAll()).thenReturn(List.of(
                new RetentionPolicyEntity(MessageType.EMAIL, Duration.ofMinutes(2), "seed"),
                new RetentionPolicyEntity(MessageType.CHAT, Duration.ofMinutes(1), "seed")));
        Instant now = Instant.parse("2026-09-08T12:00:00Z");

        Map<MessageType, Instant> cutoffs = service.cutoffs(now);

        assertThat(cutoffs.get(MessageType.EMAIL)).isEqualTo(Instant.parse("2026-09-08T11:58:00Z"));
        assertThat(cutoffs.get(MessageType.CHAT)).isEqualTo(Instant.parse("2026-09-08T11:59:00Z"));
    }

    @Test
    void fallbackAppliesToATypeWithNoConfiguredDefault() {
        RetentionDefaults sparse = new RetentionDefaults(Duration.ofDays(2555), Map.of());

        // A type nobody configured should be kept, not destroyed.
        assertThat(sparse.seedFor(MessageType.CHAT)).isEqualTo(Duration.ofDays(2555));
    }
}
