package com.discoveryhub.holds.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite key for {@link HoldCoverageEntity}: a (holdId, messageId) pair. A message can be
 * covered by many holds, and a hold covers many messages, so coverage is a pure many-to-many
 * table — but the row is the unit that matters, so it is its own entity keyed by the pair.
 */
public class HoldCoverageId implements Serializable {

    private String holdId;
    private String messageId;

    public HoldCoverageId() {
    }

    public HoldCoverageId(String holdId, String messageId) {
        this.holdId = holdId;
        this.messageId = messageId;
    }

    public String getHoldId() { return holdId; }
    public String getMessageId() { return messageId; }

    @Override
    public boolean equals(Object o) {
        return o instanceof HoldCoverageId that
                && Objects.equals(holdId, that.holdId)
                && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(holdId, messageId);
    }
}
