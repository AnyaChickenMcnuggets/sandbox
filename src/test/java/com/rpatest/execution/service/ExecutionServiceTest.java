package com.rpatest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.ScenarioExecutionEngine;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.RunResponse;
import com.rpatest.execution.web.StepRunResponse;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.domain.TestScenario;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import com.rpatest.scenario.repository.TestScenarioRepository;
import java.util.Map;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        when(scenarioRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startRun(1L, "tester")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void startRunPersistsRunAndSubmitsExecution() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        ScenarioRun run = run(100L, 1L);
        when(runRepository.save(any())).thenReturn(run);

        RunResponse response = service.startRun(1L, "tester");

        assertThat(response.id()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(RunStatus.PENDING);
        verify(engine).runScenario(100L, null);
    }

    @Test
    void startRunSavesScenarioNameDenormalizedOnTheRun() {
        // имя сценария сохраняется на StepRun в момент запуска — история прогонов не должна
        // зависеть от отдельного GET .../scenarios/{id} и должна пережить удаление сценария
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        when(runRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startRun(1L, "tester");

        ArgumentCaptor<ScenarioRun> captor = ArgumentCaptor.forClass(ScenarioRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getScenarioName()).isEqualTo("My Scenario");
    }

    @Test
    void startRunWithStartStepIdValidatesOwnershipAndPassesItToEngine() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        ScenarioStep startStep = new ScenarioStep(1L, ScenarioStepType.JOB, "job", Map.of(), 2);
        setId(startStep, 7L);
        when(scenarioStepRepository.findById(7L)).thenReturn(Optional.of(startStep));
        ScenarioRun run = run(100L, 1L);
        when(runRepository.save(any())).thenReturn(run);

        RunResponse response = service.startRun(1L, "tester", 7L);

        assertThat(response.id()).isEqualTo(100L);
        verify(engine).runScenario(100L, 7L);
    }

    @Test
    void startRunThrowsWhenStartStepDoesNotBelongToScenario() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        ScenarioStep foreignStep = new ScenarioStep(999L, ScenarioStepType.JOB, "job", Map.of(), 0);
        setId(foreignStep, 7L);
        when(scenarioStepRepository.findById(7L)).thenReturn(Optional.of(foreignStep));

        assertThatThrownBy(() -> service.startRun(1L, "tester", 7L)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void startRunThrowsWhenStartStepMissing() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        when(scenarioStepRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startRun(1L, "tester", 7L)).isInstanceOf(NotFoundException.class);
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
    void getRunReturnsDenormalizedScenarioName() {
        ScenarioRun run = new ScenarioRun(1L, "tester", null, "My Scenario");
        setId(run, 100L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of());

        RunResponse response = service.getRun(100L);

        assertThat(response.scenarioName()).isEqualTo("My Scenario");
    }

    @Test
    void getRunUsesDenormalizedStepNameAndTypeWhenScenarioStepNoLongerExists() {
        // после удаления/пересоздания scenario_step (см. V9-миграция) step_id у старого StepRun
        // становится null — имя/тип шага должны при этом браться из самого StepRun, а не из
        // live-join, который в этом случае ничего не найдёт
        ScenarioRun run = run(100L, 1L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));
        StepRun orphanedStep = new StepRun(100L, 5L, "Old Step Name", ScenarioStepType.JOB);
        setStepIdToNull(orphanedStep);
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of(orphanedStep));

        RunResponse response = service.getRun(100L);

        StepRunResponse stepResponse = response.steps().get(0);
        assertThat(stepResponse.stepId()).isNull();
        assertThat(stepResponse.stepName()).isEqualTo("Old Step Name");
        assertThat(stepResponse.stepType()).isEqualTo(ScenarioStepType.JOB);
    }

    @Test
    void getRunOrdersStepsByScenarioPositionRegardlessOfRepositoryReturnOrder() {
        // StepRun(PENDING) заводятся все разом при старте рана (см. ScenarioExecutionEngine), а
        // findByScenarioRunId ничего не гарантирует про порядок — сортировать нужно по позиции
        // шага в сценарии, а не полагаться на порядок из БД
        ScenarioRun run = run(100L, 1L);
        when(runRepository.findById(100L)).thenReturn(Optional.of(run));

        StepRun stepRunB = new StepRun(100L, 20L);
        StepRun stepRunA = new StepRun(100L, 10L);
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of(stepRunB, stepRunA));

        ScenarioStep stepA = new ScenarioStep(1L, ScenarioStepType.QUEUE, "a", Map.of(), 0);
        setId(stepA, 10L);
        ScenarioStep stepB = new ScenarioStep(1L, ScenarioStepType.QUEUE, "b", Map.of(), 1);
        setId(stepB, 20L);
        when(scenarioStepRepository.findAllById(any())).thenReturn(List.of(stepB, stepA));

        RunResponse response = service.getRun(100L);

        assertThat(response.steps()).extracting(StepRunResponse::stepId).containsExactly(10L, 20L);
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

    private TestScenario scenario(String name) {
        return new TestScenario(name, null);
    }

    private void setStepIdToNull(StepRun stepRun) {
        try {
            Field field = StepRun.class.getDeclaredField("stepId");
            field.setAccessible(true);
            field.set(stepRun, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
