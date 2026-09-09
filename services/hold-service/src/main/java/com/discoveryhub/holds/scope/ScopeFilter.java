package com.discoveryhub.holds.scope;

import com.discoveryhub.holds.adapter.MessageReference;
import com.discoveryhub.holds.domain.HoldScope;

/**
 * Strategy (behavioural) for one dimension of hold-scope narrowing. A hold's scope is a
 * combination of custodians, an optional date range, and optional search terms (FR-4.1); each
 * dimension that is present narrows the set of messages the hold covers. Each concrete strategy
 * is one narrowing algorithm — date comparison, text matching — behind the same interface, so the
 * {@link HoldScopeResolver} applies whichever filters are active for a given scope without knowing
 * which dimensions exist.
 *
 * <p>{@link #applies(HoldScope)} reports whether this filter is active for a scope (e.g. the
 * date-range filter is inactive when the scope has no date range), and {@link #matches} decides
 * whether a single message survives this filter. A message is in scope iff it survives every
 * <i>active</i> filter.
 *
 * <p>Adding a new narrowing dimension — say, has-attachment — is a new {@code @Component}
 * implementing this interface; the resolver and the other filters do not change (OCP). That is the
 * defensible reason for Strategy over a single resolver with nested {@code if}s: the dimensions
 * vary independently and a new one is additive.
 */
public interface ScopeFilter {

    /** Whether this filter is active for the given scope — i.e. whether the scope narrows on this dimension. */
    boolean applies(HoldScope scope);

    /** Whether a message passes this filter. Only called when {@link #applies} is true. */
    boolean matches(MessageReference message, HoldScope scope);

    /** Human-readable name for the filter, used in error messages when resolution fails. */
    String name();
}
