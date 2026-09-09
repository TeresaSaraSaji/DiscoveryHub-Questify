package com.discoveryhub.holds.scope;

import com.discoveryhub.holds.adapter.MessageReference;
import com.discoveryhub.holds.domain.HoldScope;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Search-terms narrowing (FR-4.1). A simple case-insensitive substring match against the message's
 * subject and body — enough for the demo and for the "scoped by search terms" requirement, where
 * the real full-text search lives in P3's Elasticsearch index. A message passes iff every
 * whitespace-separated term appears in the subject or the body.
 *
 * <p>This is intentionally not a call to P3: a hold's scope must be the <i>exact</i> set of messages
 * at placement time, resolved against P2's system of record, not a live query that could drift as
 * the index changes. P3's search is the investigator's discovery tool; the hold's coverage is the
 * frozen legal scope.
 */
@Component
public final class SearchTermsScopeFilter implements ScopeFilter {

    @Override
    public boolean applies(HoldScope scope) {
        return scope.hasTerms();
    }

    @Override
    public boolean matches(MessageReference message, HoldScope scope) {
        String haystack = join(message.subject(), message.body());
        for (String term : scope.searchTerms().trim().split("\\s+")) {
            if (term.isBlank()) {
                continue;
            }
            if (!haystack.contains(term.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static String join(String subject, String body) {
        StringBuilder sb = new StringBuilder();
        if (subject != null) {
            sb.append(subject);
        }
        sb.append('\n');
        if (body != null) {
            sb.append(body);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    @Override
    public String name() {
        return "search-terms";
    }
}
