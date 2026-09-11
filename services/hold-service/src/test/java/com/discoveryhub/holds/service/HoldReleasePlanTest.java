package com.discoveryhub.holds.service;

import com.discoveryhub.holds.repository.HoldCoverageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The plan splits a hold's coverage into the messages a release frees and the messages another
 * active hold keeps. Everything downstream — which release events go out, what the audit says —
 * follows from that split, so it is pinned here on its own.
 */
@ExtendWith(MockitoExtension.class)
class HoldReleasePlanTest {

    @Mock HoldCoverageRepository coverage;

    @Test
    void withNoOverlapEveryCoveredMessageIsFreed() {
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m1", "m2", "m3"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of("m1", "m2", "m3"));

        HoldReleasePlan plan = HoldReleasePlan.forRelease(coverage, "hold-a");

        assertThat(plan.unprotected()).containsExactly("m1", "m2", "m3");
        assertThat(plan.stillHeld()).isEmpty();
        assertThat(plan.hasOverlap()).isFalse();
    }

    @Test
    void aMessageCoveredByASecondActiveHoldIsNotFreed() {
        // Hold A = Rahul's emails, hold B = the Phoenix investigation, both covering m123.
        // Releasing A frees A's other messages but not m123.
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m123", "m124"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of("m124"));

        HoldReleasePlan plan = HoldReleasePlan.forRelease(coverage, "hold-a");

        assertThat(plan.unprotected()).containsExactly("m124");
        assertThat(plan.stillHeld()).containsExactly("m123");
        assertThat(plan.hasOverlap()).isTrue();
    }

    @Test
    void aHoldWhollyShadowedByAnotherFreesNothing() {
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m1", "m2"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of());

        HoldReleasePlan plan = HoldReleasePlan.forRelease(coverage, "hold-a");

        assertThat(plan.unprotected()).isEmpty();
        assertThat(plan.stillHeld()).containsExactly("m1", "m2");
        assertThat(plan.covered()).hasSize(2);
    }

    @Test
    void emptyCoverageSkipsTheOverlapQueryEntirely() {
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of());

        HoldReleasePlan plan = HoldReleasePlan.forRelease(coverage, "hold-a");

        assertThat(plan.covered()).isEmpty();
        assertThat(plan.unprotected()).isEmpty();
        assertThat(plan.stillHeld()).isEmpty();
        assertThat(plan.hasOverlap()).isFalse();
        verify(coverage, never()).findMessageIdsUnprotectedByReleasing(any());
    }

    @Test
    void coveredIsTheUnionOfTheTwoHalves() {
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(List.of("m1", "m2", "m3", "m4"));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a")).thenReturn(List.of("m2", "m4"));

        HoldReleasePlan plan = HoldReleasePlan.forRelease(coverage, "hold-a");

        assertThat(plan.unprotected()).containsExactlyInAnyOrder("m2", "m4");
        assertThat(plan.stillHeld()).containsExactlyInAnyOrder("m1", "m3");
        assertThat(plan.unprotected().size() + plan.stillHeld().size()).isEqualTo(plan.covered().size());
    }

    @Test
    void theReturnedListsAreImmutable() {
        when(coverage.findMessageIdsByHoldId("hold-a")).thenReturn(new java.util.ArrayList<>(List.of("m1")));
        when(coverage.findMessageIdsUnprotectedByReleasing("hold-a"))
                .thenReturn(new java.util.ArrayList<>(List.of("m1")));

        HoldReleasePlan plan = HoldReleasePlan.forRelease(coverage, "hold-a");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> plan.unprotected().add("m2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
