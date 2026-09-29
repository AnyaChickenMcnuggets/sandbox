package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rpatest.orchestrator.client.RpaProjectQueuePort;
import com.rpatest.orchestrator.client.RpaProjectsPort;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectShortDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrchestratorLookupTest {

    private RpaProjectsPort rpaProjectsPort;
    private RpaProjectQueuePort rpaProjectQueuePort;
    private OrchestratorLookup lookup;

    @BeforeEach
    void setUp() {
        rpaProjectsPort = mock(RpaProjectsPort.class);
        rpaProjectQueuePort = mock(RpaProjectQueuePort.class);
        lookup = new OrchestratorLookup(rpaProjectsPort, rpaProjectQueuePort);
    }

    @Test
    void resolvesProjectIdByNameWhenNameProvided() {
        when(rpaProjectsPort.findByName("Invoice Processor"))
                .thenReturn(Optional.of(new RpaProjectShortDto(7, "Invoice Processor", null, null, true)));

        int id = lookup.resolveProjectId("Invoice Processor", null);

        assertThat(id).isEqualTo(7);
    }

    @Test
    void throwsWhenProjectNameNotFound() {
        when(rpaProjectsPort.findByName("Unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lookup.resolveProjectId("Unknown", null))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("Unknown");
    }

    @Test
    void resolvesProjectIdAsIsWhenOnlyIdProvided() {
        int id = lookup.resolveProjectId(null, 3);

        assertThat(id).isEqualTo(3);
    }

    @Test
    void resolvesProjectLabelAsNameWhenNameProvided() {
        String label = lookup.resolveProjectLabel("Invoice Processor", null);

        assertThat(label).isEqualTo("Invoice Processor");
    }

    @Test
    void resolvesProjectLabelByIdLookupWhenOnlyIdProvided() {
        when(rpaProjectsPort.findById(3)).thenReturn(Optional.of(new RpaProjectShortDto(3, "Sandbox Task", null, null, true)));

        String label = lookup.resolveProjectLabel(null, 3);

        assertThat(label).isEqualTo("Sandbox Task");
    }

    @Test
    void fallsBackToRawIdWhenLabelLookupFindsNothing() {
        when(rpaProjectsPort.findById(3)).thenReturn(Optional.empty());

        String label = lookup.resolveProjectLabel(null, 3);

        assertThat(label).isEqualTo("id=3");
    }

    @Test
    void findsQueueEntriesByAssignmentId() {
        List<QueueItemProjectDto> entries = List.of(new QueueItemProjectDto(1, 42, null, null, LocalDateTime.now(), null));
        when(rpaProjectQueuePort.findByAssignment(42)).thenReturn(entries);

        assertThat(lookup.findQueueEntries(42)).isEqualTo(entries);
    }
}
