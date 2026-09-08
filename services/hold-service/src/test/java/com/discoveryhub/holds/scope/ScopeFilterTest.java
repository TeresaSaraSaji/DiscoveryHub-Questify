package com.discoveryhub.holds.scope;

import com.discoveryhub.holds.adapter.MessageReference;
import com.discoveryhub.holds.domain.HoldScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each {@link ScopeFilter} strategy is a pure predicate over a message and a scope — tested in
 * isolation so the resolver test can trust them.
 */
class ScopeFilterTest {

    private final DateRangeScopeFilter dateFilter = new DateRangeScopeFilter();
    private final SearchTermsScopeFilter termsFilter = new SearchTermsScopeFilter();

    private MessageReference msg(String subject, String body, Instant sent) {
        return new MessageReference("msg-1", "cust-1", sent, subject, body);
    }

    // ---------------------------------------------------------- date-range filter

    @Test
    void dateRangeFilterInactiveWhenScopeHasNoDates() {
        assertThat(dateFilter.applies(new HoldScope(List.of("c"), null, null, null))).isFalse();
    }

    @Test
    void dateRangeFilterKeepsMessageInsideRange() {
        HoldScope scope = new HoldScope(List.of("c"),
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T23:59:59Z"), null);
        MessageReference m = msg("s", "b", Instant.parse("2024-06-15T12:00:00Z"));

        assertThat(dateFilter.matches(m, scope)).isTrue();
    }

    @Test
    void dateRangeFilterDropsMessageBeforeFrom() {
        HoldScope scope = new HoldScope(List.of("c"),
                Instant.parse("2024-06-01T00:00:00Z"), null, null);
        MessageReference m = msg("s", "b", Instant.parse("2024-05-15T12:00:00Z"));

        assertThat(dateFilter.matches(m, scope)).isFalse();
    }

    @Test
    void dateRangeFilterDropsMessageAfterTo() {
        HoldScope scope = new HoldScope(List.of("c"),
                null, Instant.parse("2024-06-01T00:00:00Z"), null);
        MessageReference m = msg("s", "b", Instant.parse("2024-06-15T12:00:00Z"));

        assertThat(dateFilter.matches(m, scope)).isFalse();
    }

    // ---------------------------------------------------------- search-terms filter

    @Test
    void termsFilterInactiveWhenScopeHasNoTerms() {
        assertThat(termsFilter.applies(new HoldScope(List.of("c"), null, null, null))).isFalse();
        assertThat(termsFilter.applies(new HoldScope(List.of("c"), null, null, "  "))).isFalse();
    }

    @Test
    void termsFilterMatchesWhenAllTermsPresent() {
        HoldScope scope = new HoldScope(List.of("c"), null, null, "trade secret");
        MessageReference m = msg("insider trade", "the secret file", Instant.now());

        assertThat(termsFilter.matches(m, scope)).isTrue();
    }

    @Test
    void termsFilterDropsWhenATermIsAbsent() {
        HoldScope scope = new HoldScope(List.of("c"), null, null, "trade missing");
        MessageReference m = msg("insider trade", "the secret file", Instant.now());

        assertThat(termsFilter.matches(m, scope)).isFalse();
    }

    @Test
    void termsFilterIsCaseInsensitive() {
        HoldScope scope = new HoldScope(List.of("c"), null, null, "TRADE");
        MessageReference m = msg("insider", "the trade file", Instant.now());

        assertThat(termsFilter.matches(m, scope)).isTrue();
    }
}
