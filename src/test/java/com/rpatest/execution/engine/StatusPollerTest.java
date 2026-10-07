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
import com.rpatest.orchestrator.client.RpaProjectsPort;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatusPollerTest {

    private static final Duration TIMEOUT = Duration.ofMillis(150);

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
        OrchestratorLookup orchestratorLookup = new OrchestratorLookup(mock(RpaProjectsPort.class), rpaProjectQueuePort);
        poller = new StatusPoller(rpaProjectLaunchesPort, orchestratorLookup, progressReporter, properties);
        stepRun = new StepRun(1L, 2L);
    }

    @Test
    void returnsImmediatelyWhenLaunchAlreadyCompleted() {
        RpaProjectLaunchDto launch = launch(LocalDateTime.now(), true);
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of(launch));

        RpaProjectLaunchDto result = poller.pollUntilTerminal(stepRun, 1, "job-1", TIMEOUT, null);

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

        RpaProjectLaunchDto result = poller.pollUntilTerminal(stepRun, 1, "job-1", TIMEOUT, null);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void throwsWithQueueDiagnosticsWhenNeverPickedUpByRobot() {
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of());
        when(rpaProjectQueuePort.findByAssignment(1))
                .thenReturn(List.of(new QueueItemProjectDto(1, 1, null, null, LocalDateTime.now(), null)));

        assertThatThrownBy(() -> poller.pollUntilTerminal(stepRun, 1, "job-1", TIMEOUT, null))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("project queue");
    }

    @Test
    void throwsWithNotFoundDiagnosticsWhenNeitherQueuedNorLaunched() {
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of());
        when(rpaProjectQueuePort.findByAssignment(1)).thenReturn(List.of());

        assertThatThrownBy(() -> poller.pollUntilTerminal(stepRun, 1, "job-1", TIMEOUT, null))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("not found in the project queue nor among robot launches");
    }

    @Test
    void throwsWithRobotDiagnosticsWhenStuckRunning() {
        RpaProjectLaunchDto running = new RpaProjectLaunchDto(
                1, 7, 5, "robot-1", 1, LocalDateTime.now(), null, null, null, LocalDateTime.now());
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of(running));

        assertThatThrownBy(() -> poller.pollUntilTerminal(stepRun, 1, "job-1", TIMEOUT, null))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("robot-1");
    }

    @Test
    void reportsAssignmentByLabelNotRawIdOnCompletion() {
        // статусы прогона должны показывать человекочитаемое имя задания, а не голый numeric id —
        // в реальном ответе detail выглядел как "Задание id=1447 ...", что было непонятно
        RpaProjectLaunchDto launch = launch(LocalDateTime.now(), true);
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of(launch));

        poller.pollUntilTerminal(stepRun, 1, "First Job_24_36", TIMEOUT, null);

        assertThat(stepRun.getDetail()).contains("First Job_24_36");
        assertThat(stepRun.getDetail()).doesNotContain("id=1");
    }

    @Test
    void withoutTimeoutKeepsPollingPastWhatWouldBeTimeoutAndFinishesWhenLaunchCompletes() {
        // timeout=null — никакого скрытого дедлайна: 30 пустых опросов подряд (интервал 10мс =
        // 300мс, вдвое больше TIMEOUT) не роняют шаг, он дожидается завершения
        RpaProjectLaunchDto completed = launch(LocalDateTime.now(), true);
        org.mockito.stubbing.OngoingStubbing<List<RpaProjectLaunchDto>> stub =
                when(rpaProjectLaunchesPort.getByAssignment(1));
        for (int i = 0; i < 30; i++) {
            stub = stub.thenReturn(List.of());
        }
        stub.thenReturn(List.of(completed));
        when(rpaProjectQueuePort.findByAssignment(1)).thenReturn(List.of());

        RpaProjectLaunchDto result = poller.pollUntilTerminal(stepRun, 1, "job-1", null, null);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void explicitIntervalOverridesDefault() {
        // Интервал по умолчанию — 1 час: если бы он использовался, второго опроса не было бы
        // никогда. Явный интервал из сценария (1мс) даёт несколько опросов в пределах таймаута —
        // без привязки к скорости машины/консоли.
        OrchestratorProperties slowDefault = new OrchestratorProperties();
        slowDefault.getPolling().setInterval(Duration.ofHours(1));
        StatusPoller pollerWithSlowDefault = new StatusPoller(
                rpaProjectLaunchesPort,
                new OrchestratorLookup(mock(RpaProjectsPort.class), rpaProjectQueuePort),
                new StepProgressReporter(mock(StepRunRepository.class)),
                slowDefault);
        when(rpaProjectLaunchesPort.getByAssignment(1)).thenReturn(List.of());
        when(rpaProjectQueuePort.findByAssignment(1)).thenReturn(List.of());

        assertThatThrownBy(() ->
                pollerWithSlowDefault.pollUntilTerminal(stepRun, 1, "job-1", TIMEOUT, Duration.ofMillis(1)))
                .isInstanceOf(StepExecutionException.class);

        org.mockito.Mockito.verify(rpaProjectLaunchesPort, org.mockito.Mockito.atLeast(2)).getByAssignment(1);
    }

    private RpaProjectLaunchDto launch(LocalDateTime startedAt, boolean success) {
        return new RpaProjectLaunchDto(1, 7, 5, "robot-1", 1, startedAt, LocalDateTime.now(), success, null, startedAt);
    }

    private RpaProjectLaunchDto launchWithSuccess(LocalDateTime startedAt, boolean success) {
        return launch(startedAt, success);
    }
}
