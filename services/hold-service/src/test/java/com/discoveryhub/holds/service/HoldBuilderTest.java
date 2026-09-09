package com.discoveryhub.holds.service;

import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldScope;
import com.discoveryhub.holds.domain.HoldStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A new hold must satisfy its invariants: a case id and at least one custodian. */
class HoldBuilderTest {

    @Test
    void buildsResolvingHoldWithScopeApplied() {
        HoldScope scope = new HoldScope(List.of("cust-1", "cust-2"),
                Instant.parse("2024-01-01T00:00:00Z"), null, "trade");

        HoldEntity hold = HoldBuilder.create().caseId("case-1").scope(scope).build();

        assertThat(hold.getHoldId()).isNotBlank();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RESOLVING);
        assertThat(hold.getCaseId()).isEqualTo("case-1");
        assertThat(hold.custodianList()).containsExactly("cust-1", "cust-2");
        assertThat(hold.getSearchTerms()).isEqualTo("trade");
        assertThat(hold.getDateFrom()).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
    }

    @Test
    void rejectsBlankCaseId() {
        assertThatThrownBy(() -> HoldBuilder.create().caseId(" ").scope(new HoldScope(List.of("c"), null, null, null)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsScopeWithNoCustodians() {
        assertThatThrownBy(() -> HoldBuilder.create().caseId("case-1").scope(new HoldScope(List.of(), null, null, null)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullScope() {
        assertThatThrownBy(() -> HoldBuilder.create().caseId("case-1").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
