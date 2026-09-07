package com.discoveryhub.ingestion.service;

import com.discoveryhub.contracts.Ids;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lives here rather than in the contracts module so that shared module stays dependency-free.
 * P1 is the service that derives ids, so it is the service that has to guarantee they are stable.
 */
class IdsTest {

    @Test
    void messageIdIsStableAcrossRuns() {
        // Pinned literal on purpose: if this value changes, every fixture and demo script loaded
        // on another machine stops matching, so the change must be deliberate and visible.
        assertThat(Ids.messageId("EXCH-000123"))
                .isEqualTo("8ba86767-9814-3e61-9cab-1f4b04c5160e");
    }

    @Test
    void messageIdIsAFunctionOfExternalIdOnly() {
        assertThat(Ids.messageId("EXCH-1")).isEqualTo(Ids.messageId("EXCH-1"));
        assertThat(Ids.messageId("EXCH-1")).isNotEqualTo(Ids.messageId("EXCH-2"));
    }

    @Test
    void attachmentIdVariesByIndex() {
        assertThat(Ids.attachmentId("EXCH-1", 0)).isNotEqualTo(Ids.attachmentId("EXCH-1", 1));
        assertThat(Ids.attachmentId("EXCH-1", 0)).isEqualTo(Ids.attachmentId("EXCH-1", 0));
    }

    @Test
    void namespacesDoNotCollide() {
        assertThat(Ids.messageId("X")).isNotEqualTo(Ids.threadId("X"));
        assertThat(Ids.messageId("X")).isNotEqualTo(Ids.attachmentId("X", 0));
    }
}
