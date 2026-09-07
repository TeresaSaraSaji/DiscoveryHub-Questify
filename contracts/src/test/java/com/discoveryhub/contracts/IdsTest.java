package com.discoveryhub.contracts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdsTest {

    @Test
    void messageIdIsStableAcrossRuns() {
        // Pinned literal on purpose: if this value ever changes, fixtures and demo scripts loaded
        // on another machine stop matching, so the change must be deliberate and visible.
        assertThat(Ids.messageId("EXCH-000123").toString())
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

    @Test
    void blankExternalIdIsRejected() {
        assertThatThrownBy(() -> Ids.messageId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("externalId");
    }
}
