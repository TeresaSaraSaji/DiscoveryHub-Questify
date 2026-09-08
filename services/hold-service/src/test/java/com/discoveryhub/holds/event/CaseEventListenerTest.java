package com.discoveryhub.holds.event;

import com.discoveryhub.contracts.CaseEvent;
import com.discoveryhub.holds.command.HoldCommand;
import com.discoveryhub.holds.command.HoldCommandFactory;
import com.discoveryhub.holds.domain.HoldEntity;
import com.discoveryhub.holds.domain.HoldStatus;
import com.discoveryhub.holds.repository.HoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Observer reacts only to {@code case.closed} and releases every active hold on the case.
 * Non-close events are ignored; a case with no active holds is a no-op; one hold's failure does
 * not stop the others.
 */
@ExtendWith(MockitoExtension.class)
class CaseEventListenerTest {

    @Mock HoldRepository holds;
    @Mock HoldCommandFactory commandFactory;
    @Mock HoldCommand releaseCommand;

    private CaseEventListener listener;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper();
        listener = new CaseEventListener(holds, commandFactory, json);
    }

    @Test
    void closesCaseReleasesAllActiveHolds() throws Exception {
        String payload = json.writeValueAsString(
                new CaseEvent("case-1", "case.closed", "CLOSED", "UNDER_REVIEW", "corr-1", Instant.now()));
        HoldEntity h1 = new HoldEntity("h1", "case-1", HoldStatus.ACTIVE, Instant.now());
        HoldEntity h2 = new HoldEntity("h2", "case-1", HoldStatus.ACTIVE, Instant.now());
        when(holds.findByCaseIdAndStatus("case-1", HoldStatus.ACTIVE)).thenReturn(List.of(h1, h2));
        when(commandFactory.releaseForCaseClosure(any(), any())).thenReturn(releaseCommand);

        listener.onCaseEvent(payload);

        verify(commandFactory).releaseForCaseClosure(eq(h1), any());
        verify(commandFactory).releaseForCaseClosure(eq(h2), any());
        verify(releaseCommand, org.mockito.Mockito.times(2)).execute();
    }

    @Test
    void ignoresNonCloseEvents() throws Exception {
        String payload = json.writeValueAsString(
                new CaseEvent("case-1", "case.transitioned", "ACTIVE", "DRAFT", "corr-1", Instant.now()));

        listener.onCaseEvent(payload);

        verify(holds, never()).findByCaseIdAndStatus(any(), any());
    }

    @Test
    void noActiveHoldsIsANoop() throws Exception {
        String payload = json.writeValueAsString(
                new CaseEvent("case-1", "case.closed", "CLOSED", "UNDER_REVIEW", "corr-1", Instant.now()));
        when(holds.findByCaseIdAndStatus("case-1", HoldStatus.ACTIVE)).thenReturn(List.of());

        listener.onCaseEvent(payload);

        verify(commandFactory, never()).releaseForCaseClosure(any(), any());
    }

    @Test
    void unparseablePayloadIsSkipped() {
        listener.onCaseEvent("not json");
        // completes without throwing
    }
}
