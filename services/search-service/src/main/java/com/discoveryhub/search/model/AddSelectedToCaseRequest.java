package com.discoveryhub.search.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Hand-picked variant of the bulk add: file exactly these message ids onto a case.
 *
 * <p>Where {@link BulkAddToCaseRequest} describes a <i>search</i> whose matches get filed (a page
 * or every match), this carries the ids themselves — the reviewer looked at the results and ticked
 * the ones that matter. No {@link SearchRequest} is re-run, because re-running one could match a
 * different set than the reviewer saw and ticked; the ids are the decision, verbatim.
 */
public record AddSelectedToCaseRequest(
        @NotBlank String caseId,
        @NotEmpty List<String> messageIds) {

    public AddSelectedToCaseRequest {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
