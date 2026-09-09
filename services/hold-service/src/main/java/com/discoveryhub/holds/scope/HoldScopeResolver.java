package com.discoveryhub.holds.scope;

import com.discoveryhub.contracts.Message;
import com.discoveryhub.holds.adapter.MessageReference;
import com.discoveryhub.holds.adapter.MessageReferenceAdapter;
import com.discoveryhub.holds.client.ArchiveMessageClient;
import com.discoveryhub.holds.domain.HoldScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a {@link HoldScope} to the exact, de-duplicated set of messageIds it covers, using the
 * {@link ScopeFilter} strategies for narrowing and the {@link ArchiveMessageClient} for enumeration.
 *
 * <p>Resolution is custodian-driven: the hold's custodians are the enumeration base (we list each
 * custodian's mailbox from P2), and every active {@link ScopeFilter} narrows that set. A scope
 * with no custodians is illegal — a hold with no custodians would either cover nothing (useless)
 * or cover everything (dangerous, and not what "scoped by custodian" means) — so an empty
 * custodian list raises and the hold is rejected at the API layer instead.
 *
 * <p>The filters are injected as a list, so Spring supplies every {@code @Component} implementing
 * {@link ScopeFilter}; the resolver applies only those whose {@link ScopeFilter#applies} is true
 * for the scope. The order of filters does not matter — each is a pure predicate — but a
 * {@link LinkedHashSet} keeps the resolved ids stable for reproducible coverage and audit.
 *
 * <p>Throws {@link EmptyScopeException} on a zero-message result so the worker never persists
 * empty coverage. Throws on any P2 communication failure (propagated from the client) so the worker
 * marks the hold FAILED rather than silently holding nothing.
 */
@Component
public class HoldScopeResolver {

    private static final Logger log = LoggerFactory.getLogger(HoldScopeResolver.class);

    private final ArchiveMessageClient archive;
    private final MessageReferenceAdapter adapter;
    private final List<ScopeFilter> filters;

    public HoldScopeResolver(ArchiveMessageClient archive, MessageReferenceAdapter adapter,
                             List<ScopeFilter> filters) {
        this.archive = archive;
        this.adapter = adapter;
        this.filters = filters;
    }

    /**
     * @param holdId the hold being resolved, for the {@link EmptyScopeException} message
     * @return the de-duplicated messageIds in scope, in stable order
     */
    public List<String> resolve(String holdId, HoldScope scope) {
        if (scope.custodians().isEmpty()) {
            throw new IllegalArgumentException("hold scope must name at least one custodian");
        }

        List<ScopeFilter> active = activeFilters(scope);
        log.info("resolving hold {} for {} custodian(s) with filters {}",
                holdId, scope.custodians().size(), active.stream().map(ScopeFilter::name).toList());

        Set<String> messageIds = new LinkedHashSet<>();
        for (String custodianId : scope.custodians()) {
            for (Message message : archive.listByCustodian(custodianId)) {
                MessageReference ref = adapter.adapt(message);
                if (passesActiveFilters(ref, scope, active)) {
                    messageIds.add(ref.messageId());
                }
            }
        }

        if (messageIds.isEmpty()) {
            throw new EmptyScopeException(holdId);
        }
        log.info("hold {} scope resolved to {} messages", holdId, messageIds.size());
        return new ArrayList<>(messageIds);
    }

    private List<ScopeFilter> activeFilters(HoldScope scope) {
        List<ScopeFilter> active = new ArrayList<>();
        for (ScopeFilter filter : filters) {
            if (filter.applies(scope)) {
                active.add(filter);
            }
        }
        return active;
    }

    private static boolean passesActiveFilters(MessageReference ref, HoldScope scope, List<ScopeFilter> active) {
        for (ScopeFilter filter : active) {
            if (!filter.matches(ref, scope)) {
                return false;
            }
        }
        return true;
    }
}
