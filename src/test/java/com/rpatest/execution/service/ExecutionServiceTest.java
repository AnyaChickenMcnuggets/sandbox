package com.rpatest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.ScenarioExecutionEngine;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.RunResponse;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import com.rpatest.scenario.repository.TestScenarioRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionServiceTest {

    private TestScenarioRepository scenarioRepository;
    private ScenarioRunRepository runRepository;
    private StepRunRepository stepRunRepository;
    private ScenarioStepRepository scenarioStepRepository;
    private ScenarioExecutionEngine engine;
    private AssignmentsPort assignmentsPort;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        scenarioRepository = mock(TestScenarioRepository.class);
        runRepository = mock(ScenarioRunRepository.class);
        stepRunRepository = mock(StepRunRepository.class);
        scenarioStepRepository = mock(ScenarioStepRepository.class);
        engine = mock(ScenarioExecutionEngine.class);
        assignmentsPort = mock(AssignmentsPort.class);
        Executor synchronousExecutor = Runnable::run;
        service = new ExecutionService(scenarioRepository, runRepository, stepRunRepository, scenarioStepRepository,
                engine, assignmentsPort, synchronousExecutor);
    }

    @Test
    void startRunThrowsWhenScenarioMissing() {
        when(scenarioRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.startRun(1L, "tester")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void startRunPersistsRunAndSubmitsExecution() {
        when(scenarioRepository.existsById(1L)).thenReturn(true);
        ScenarioRun run = run(100L, 1L);
        when(runRepository.save(any())).thenReturn(run);

        RunResponse response = service.startRun(1L, "tester");

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(RunStatus.PENDING);
        verify(engine).runScenario(100L);
    }

    @Test
    void getRunThrowsWhenMissing() {
        when(runRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRun(1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getRunReturnsStepsFromRepository() {
        ScenarioRun run = run(100L, 1L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));
        StepRun stepRun = new StepRun(100L, 5L);
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of(stepRun));

        RunResponse response = service.getRun(100L);

        assertThat(response.steps()).hasSize(1);
        assertThat(response.steps().get(0).stepId()).isEqualTo(5L);
    }

    @Test
    void stopRunStopsRunningStepsAndMarksRunStopped() {
        ScenarioRun run = run(100L, 1L);
        run.markRunning();
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));
        StepRun runningStep = new StepRun(100L, 5L);
        runningStep.markRunning();
        runningStep.setOrchestratorAssignmentId(42);
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of(runningStep));

        RunResponse response = service.stopRun(100L);

        verify(assignmentsPort).stop(42);
        assertThat(response.status()).isEqualTo(RunStatus.STOPPED);
    }

    @Test
    void stopRunIsNoOpWhenAlreadyTerminal() {
        ScenarioRun run = run(100L, 1L);
        run.markRunning();
        run.finish(RunStatus.SUCCEEDED);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of());

        RunResponse response = service.stopRun(100L);

        assertThat(response.status()).isEqualTo(RunStatus.SUCCEEDED);
        verify(assignmentsPort, never()).stop(anyInt());
    }

    private ScenarioRun run(Long id, Long scenarioId) {
        ScenarioRun run = new ScenarioRun(scenarioId, "tester");
        setId(run, id);
        return run;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
