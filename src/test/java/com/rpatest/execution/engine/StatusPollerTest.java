package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.orchestrator.client.RpaProjectLaunchesPort;
import com.rpatest.orchestrator.client.RpaProjectQueuePort;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatusPollerTest {

    private RpaProjectLaunchesPort rpaProjectLaunchesPort;
    private RpaProjectQueuePort rpaProjectQueuePort;
    private StatusPoller poller;
    private StepRun stepRun;

    @BeforeEach
    void setUp() {
        rpaProjectLaunchesPort = mock(RpaProjectLaunchesPort.class);
        rpaProjectQueuePort = mock(RpaProjectQueuePort.class);
        StepProgressReporter progressReporter = new StepProgressReporter(mock(StepRunRepository.class));
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.getPolling().setInterval(Duration.ofMillis(10));
        properties.getPolling().setTimeout(Duration.ofMillis(150));
        poller = new StatusPoller(rpaProjectLaunchesPort, rpaProjectQueuePort, progressReporter, properties);
        stepRun = new StepRun(1L, 2L);
    }

    @Test
    void returnsImmediatelyWhenLaunchAlreadyCompleted() {
        RpaProjectLaunchDto launch = launch(LocalDateTime.now(), true);
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of(launch));

        RpaProjectLaunchDto result = poller.pollUntilTerminal(stepRun, 1);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void pollsUntilLaunchAppearsAndCompletes() {
        RpaProjectLaunchDto running = new RpaProjectLaunchDto(
                1, 7, 5, "robot-1", 1, LocalDateTime.now(), null, null, null, LocalDateTime.now());
        RpaProjectLaunchDto completed = launchWithSuccess(LocalDateTime.now(), false);
        when(rpaProjectLaunchesPort.getByAssignment(1))
                .thenReturn(List.of())
                .thenReturn(List.of(running))
                .thenReturn(List.of(completed));

        RpaProjectLaunchDto result = poller.pollUntilTerminal(stepRun, 1);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void throwsWithQueueDiagnosticsWhenNeverPickedUpByRobot() {
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of());
        when(rpaProjectQueuePort.findByAssignment(1))
                .thenReturn(List.of(new QueueItemProjectDto(1, 1, null, null, LocalDateTime.now(), null)));

        assertThatThrownBy(() -> poller.pollUntilTerminal(stepRun, 1))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("в очереди проектов");
    }

    @Test
    void throwsWithNotFoundDiagnosticsWhenNeitherQueuedNorLaunched() {
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of());
        when(rpaProjectQueuePort.findByAssignment(1)).thenReturn(List.of());

        assertThatThrownBy(() -> poller.pollUntilTerminal(stepRun, 1))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("не найдено ни в очереди проектов, ни среди запусков");
    }

    @Test
    void throwsWithRobotDiagnosticsWhenStuckRunning() {
        RpaProjectLaunchDto running = new RpaProjectLaunchDto(
                1, 7, 5, "robot-1", 1, LocalDateTime.now(), null, null, null, LocalDateTime.now());
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of(running));

        assertThatThrownBy(() -> poller.pollUntilTerminal(stepRun, 1))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("robot-1");
    }

    private RpaProjectLaunchDto launch(LocalDateTime startedAt, boolean success) {
        return new RpaProjectLaunchDto(1, 7, 5, "robot-1", 1, startedAt, LocalDateTime.now(), success, null, startedAt);
    }

    private RpaProjectLaunchDto launchWithSuccess(LocalDateTime startedAt, boolean success) {
        return launch(startedAt, success);
    }
}
