package com.discoveryhub.holds.scope;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.contracts.MessageType;
import com.discoveryhub.holds.adapter.MessageReferenceAdapter;
import com.discoveryhub.holds.client.ArchiveMessageClient;
import com.discoveryhub.holds.domain.HoldScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The resolver composes the custodian enumeration (from P2, mocked) with the real Strategy filters.
 * Tests prove that active filters narrow and inactive filters are skipped, that the result is
 * de-duplicated, and that an empty scope is refused (never persisted as empty coverage).
 */
@ExtendWith(MockitoExtension.class)
class HoldScopeResolverTest {

    @Mock ArchiveMessageClient archive;

    private HoldScopeResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new HoldScopeResolver(archive, new MessageReferenceAdapter(),
                List.of(new DateRangeScopeFilter(), new SearchTermsScopeFilter()));
    }

    private Message email(String id, String custodian, Instant sent, String subject, String body) {
        return new Message(id, "ext-" + id, "EXCHANGE", MessageType.EMAIL, custodian,
                "from@x.com", List.of("to@x.com"), List.of(), subject, body, sent, "thread-1", null, List.of(), List.of());
    }

    @Test
    void resolvesAllMessagesForCustodianWhenNoFiltersActive() {
        when(archive.listByCustodian("cust-1")).thenReturn(List.of(
                email("m1", "cust-1", Instant.parse("2024-01-01T00:00:00Z"), "s", "b"),
                email("m2", "cust-1", Instant.parse("2024-06-01T00:00:00Z"), "s", "b")));

        List<String> ids = resolver.resolve("hold-1", new HoldScope(List.of("cust-1"), null, null, null));

        assertThat(ids).containsExactly("m1", "m2");
    }

    @Test
    void appliesDateRangeFilter() {
        when(archive.listByCustodian("cust-1")).thenReturn(List.of(
                email("m1", "cust-1", Instant.parse("2024-01-01T00:00:00Z"), "s", "b"),
                email("m2", "cust-1", Instant.parse("2024-06-01T00:00:00Z"), "s", "b")));

        HoldScope scope = new HoldScope(List.of("cust-1"),
                Instant.parse("2024-03-01T00:00:00Z"), null, null);
        List<String> ids = resolver.resolve("hold-1", scope);

        assertThat(ids).containsExactly("m2");
    }

    @Test
    void appliesSearchTermsFilter() {
        when(archive.listByCustodian("cust-1")).thenReturn(List.of(
                email("m1", "cust-1", Instant.now(), "insider trade", "the secret file"),
                email("m2", "cust-1", Instant.now(), "lunch", "what time?")));

        HoldScope scope = new HoldScope(List.of("cust-1"), null, null, "trade");
        List<String> ids = resolver.resolve("hold-1", scope);

        assertThat(ids).containsExactly("m1");
    }

    @Test
    void combinesDateRangeAndTermsFilters() {
        when(archive.listByCustodian("cust-1")).thenReturn(List.of(
                email("m1", "cust-1", Instant.parse("2024-01-01T00:00:00Z"), "trade", "b"),
                email("m2", "cust-1", Instant.parse("2024-06-01T00:00:00Z"), "trade", "b"),
                email("m3", "cust-1", Instant.parse("2024-06-01T00:00:00Z"), "lunch", "b")));

        HoldScope scope = new HoldScope(List.of("cust-1"),
                Instant.parse("2024-03-01T00:00:00Z"), null, "trade");
        List<String> ids = resolver.resolve("hold-1", scope);

        assertThat(ids).containsExactly("m2");
    }

    @Test
    void deDuplicatesAcrossCustodians() {
        when(archive.listByCustodian("cust-1")).thenReturn(List.of(
                email("m1", "cust-1", Instant.now(), "s", "b")));
        when(archive.listByCustodian("cust-2")).thenReturn(List.of(
                email("m1", "cust-2", Instant.now(), "s", "b"))); // same messageId, different custodian

        List<String> ids = resolver.resolve("hold-1", new HoldScope(List.of("cust-1", "cust-2"), null, null, null));

        assertThat(ids).containsExactly("m1");
    }

    @Test
    void emptyScopeResolutionThrows() {
        when(archive.listByCustodian("cust-1")).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolve("hold-1", new HoldScope(List.of("cust-1"), null, null, null)))
                .isInstanceOf(EmptyScopeException.class);
    }

    @Test
    void scopeWithNoCustodiansIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("hold-1", new HoldScope(List.of(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
