package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rpatest.config.OrchestratorProperties;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.dto.AssignmentStatus;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatusPollerTest {

    private AssignmentsPort assignmentsPort;
    private OrchestratorProperties properties;
    private StatusPoller poller;

    @BeforeEach
    void setUp() {
        assignmentsPort = mock(AssignmentsPort.class);
        properties = new OrchestratorProperties();
        properties.getPolling().setInterval(Duration.ofMillis(10));
        properties.getPolling().setTimeout(Duration.ofMillis(200));
        poller = new StatusPoller(assignmentsPort, properties);
    }

    @Test
    void returnsImmediatelyWhenAlreadyTerminal() {
        when(assignmentsPort.get(1))
                .thenReturn(new AssignmentDto(1, "n", "d", 1, AssignmentStatus.COMPLETE, null, null, null));

        AssignmentDto result = poller.pollUntilTerminal(1);

        assertThat(result.status()).isEqualTo(AssignmentStatus.COMPLETE);
    }

    @Test
    void pollsUntilTerminalStatusReached() {
        when(assignmentsPort.get(1))
                .thenReturn(new AssignmentDto(1, "n", "d", 1, AssignmentStatus.RUNNING, null, null, null))
                .thenReturn(new AssignmentDto(1, "n", "d", 1, AssignmentStatus.RUNNING, null, null, null))
                .thenReturn(new AssignmentDto(1, "n", "d", 1, AssignmentStatus.ERROR, null, null, "fail"));

        AssignmentDto result = poller.pollUntilTerminal(1);

        assertThat(result.status()).isEqualTo(AssignmentStatus.ERROR);
    }

    @Test
    void throwsOnTimeoutWhenNeverTerminal() {
        when(assignmentsPort.get(1))
                .thenReturn(new AssignmentDto(1, "n", "d", 1, AssignmentStatus.RUNNING, null, null, null));

        assertThatThrownBy(() -> poller.pollUntilTerminal(1)).isInstanceOf(StepExecutionException.class);
    }
}
