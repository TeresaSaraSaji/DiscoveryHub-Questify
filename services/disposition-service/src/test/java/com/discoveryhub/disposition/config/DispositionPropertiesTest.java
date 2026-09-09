package com.discoveryhub.disposition.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The defaults, pinned. Every one of these is a safety property rather than a preference, and each
 * has a specific failure behind it.
 */
class DispositionPropertiesTest {

    private final DispositionProperties defaults =
            new DispositionProperties(null, 0, null, null);

    @Test
    void theScheduledSweepIsOffByDefault() {
        // The corpus is mostly past retention, so a sweep enabled by default deletes real fixture
        // data within seconds of startup and again every tick. Observed, not theorised.
        assertThat(defaults.schedule().enabled()).isFalse();
    }

    @Test
    void theHoldCheckIsRequiredByDefault() {
        // Fail closed: hold status that cannot be verified means the message is not deleted.
        assertThat(defaults.holdCheck().enabled()).isTrue();
        assertThat(defaults.holdCheck().required()).isTrue();
    }

    @Test
    void aRunIsBoundedByDefault() {
        // An unbounded sweep would hold the whole corpus in memory and make one run's blast radius
        // the entire eligible set.
        assertThat(defaults.batchSize()).isPositive();
    }

    @Test
    void aNonPositiveBatchSizeFallsBackRatherThanDisablingTheSweep() {
        assertThat(new DispositionProperties(null, -5, null, null).batchSize()).isPositive();
        assertThat(new DispositionProperties(null, 0, null, null).batchSize()).isPositive();
    }

    @Test
    void aNonPositiveHoldCheckTimeoutFallsBackToSomethingFinite() {
        DispositionProperties.HoldCheck zero =
                new DispositionProperties.HoldCheck(true, true, Duration.ZERO);
        // A zero timeout would make every hold check fail, which fails closed and is safe — but it
        // would also make the sweep permanently useless and look like a P4 outage.
        assertThat(zero.timeout()).isPositive();
    }

    @Test
    void deleteModeDefaultsToKafkaNowThatP2HasTheConsumer() {
        assertThat(defaults.deleteMode()).isEqualTo(DispositionProperties.DeleteMode.KAFKA);
    }
}
