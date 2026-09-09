package com.discoveryhub.holds.scope;

import com.discoveryhub.holds.adapter.MessageReference;
import com.discoveryhub.holds.domain.HoldScope;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Date-range narrowing (FR-4.1). Either bound may be open ({@code null}); a message is in range iff
 * its {@code sentAt} is not before {@code dateFrom} (when present) and not after {@code dateTo}
 * (when present). Half-open bounds are deliberate: a {@code dateTo} of midnight does not silently
 * drop messages sent that day at 09:00.
 */
@Component
public final class DateRangeScopeFilter implements ScopeFilter {

    @Override
    public boolean applies(HoldScope scope) {
        return scope.hasDateRange();
    }

    @Override
    public boolean matches(MessageReference message, HoldScope scope) {
        Instant sent = message.sentAt();
        if (scope.dateFrom() != null && sent.isBefore(scope.dateFrom())) {
            return false;
        }
        if (scope.dateTo() != null && sent.isAfter(scope.dateTo())) {
            return false;
        }
        return true;
    }

    @Override
    public String name() {
        return "date-range";
    }
}
