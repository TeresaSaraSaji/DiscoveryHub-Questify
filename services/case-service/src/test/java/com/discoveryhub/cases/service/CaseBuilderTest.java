package com.discoveryhub.cases.service;

import com.discoveryhub.cases.domain.CaseStatus;
import com.discoveryhub.cases.domain.MatterType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A new case must satisfy its invariants regardless of who builds it. */
class CaseBuilderTest {

    @Test
    void buildsADraftCaseWithIdAndTimestamp() {
        var entity = CaseBuilder.create()
                .name("Q3 broker review")
                .description("insider trading probe")
                .matterType(MatterType.INVESTIGATION)
                .owner("teresa")
                .build();

        assertThat(entity.getCaseId()).isNotBlank();
        assertThat(entity.getName()).isEqualTo("Q3 broker review");
        assertThat(entity.getStatus()).isEqualTo(CaseStatus.DRAFT);
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getClosedAt()).isNull();
    }

    @Test
    void blankDescriptionBecomesNull() {
        var entity = CaseBuilder.create()
                .name("case")
                .description("   ")
                .matterType(MatterType.LITIGATION)
                .owner("o")
                .build();

        assertThat(entity.getDescription()).isNull();
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> CaseBuilder.create().name(" ").owner("o").matterType(MatterType.INVESTIGATION).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankOwner() {
        assertThatThrownBy(() -> CaseBuilder.create().name("n").owner(null).matterType(MatterType.INVESTIGATION).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingMatterType() {
        assertThatThrownBy(() -> CaseBuilder.create().name("n").owner("o").build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
