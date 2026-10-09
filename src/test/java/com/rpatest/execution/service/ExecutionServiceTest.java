package com.rpatest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.PageResponse;
import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.ScenarioExecutionEngine;
import com.rpatest.execution.report.RunCompletionHandler;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.RobotAvailabilityResponse;
import com.rpatest.execution.web.RunResponse;
import com.rpatest.execution.web.RunSummaryResponse;
import com.rpatest.execution.web.StepRunResponse;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.RobotsPort;
import com.rpatest.orchestrator.dto.RobotDto;
import com.rpatest.orchestrator.dto.RobotRunStatus;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class ExecutionServiceTest {

    private TestScenarioRepository scenarioRepository;
    private ScenarioRunRepository runRepository;
    private StepRunRepository stepRunRepository;
    private ScenarioStepRepository scenarioStepRepository;
    private ScenarioExecutionEngine engine;
    private AssignmentsPort assignmentsPort;
    private RobotsPort robotsPort;
    private RunCompletionHandler completionHandler;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        scenarioRepository = mock(TestScenarioRepository.class);
        runRepository = mock(ScenarioRunRepository.class);
        stepRunRepository = mock(StepRunRepository.class);
        scenarioStepRepository = mock(ScenarioStepRepository.class);
        engine = mock(ScenarioExecutionEngine.class);
        assignmentsPort = mock(AssignmentsPort.class);
        robotsPort = mock(RobotsPort.class);
        completionHandler = mock(RunCompletionHandler.class);
        // По умолчанию роботов достаточно (2 из 2 свободны) — тесты, которые не про блокировку
        // запуска, не должны заботиться об этом сами.
        when(robotsPort.list()).thenReturn(List.of(robot(1, RobotRunStatus.IDLE), robot(2, RobotRunStatus.IDLE)));
        Executor synchronousExecutor = Runnable::run;
        service = new ExecutionService(scenarioRepository, runRepository, stepRunRepository, scenarioStepRepository,
                engine, assignmentsPort, robotsPort, new OrchestratorProperties(), completionHandler, synchronousExecutor);
    }

    @Test
    void startRunThrowsConflictWhenFewerThanMinFreeRobots() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        when(robotsPort.list()).thenReturn(List.of(
                robot(1, RobotRunStatus.IDLE), robot(2, RobotRunStatus.RUNNING), robot(3, RobotRunStatus.RUNNING)));

        assertThatThrownBy(() -> service.startRun(1L, "tester"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("1")
                .hasMessageContaining("2");
        verify(runRepository, never()).save(any());
        verify(engine, never()).runScenario(any(), any());
    }

    @Test
    void startRunSucceedsWhenExactlyMinFreeRobotsAvailable() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        when(robotsPort.list()).thenReturn(List.of(robot(1, RobotRunStatus.IDLE), robot(2, RobotRunStatus.IDLE)));
        when(runRepository.save(any())).thenReturn(run(100L, 1L));

        RunResponse response = service.startRun(1L, "tester");

        assertThat(response.id()).isEqualTo(100L);
    }

    @Test
    void startRunAllowsCustomMinFreeRobotsThreshold() {
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.setMinFreeRobots(1);
        service = new ExecutionService(scenarioRepository, runRepository, stepRunRepository, scenarioStepRepository,
                engine, assignmentsPort, robotsPort, properties, completionHandler, Runnable::run);
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        when(robotsPort.list()).thenReturn(List.of(robot(1, RobotRunStatus.IDLE)));
        when(runRepository.save(any())).thenReturn(run(100L, 1L));

        RunResponse response = service.startRun(1L, "tester");

        assertThat(response.id()).isEqualTo(100L);
    }

    @Test
    void getRobotAvailabilityReportsAllowedWhenEnoughFreeRobots() {
        when(robotsPort.list()).thenReturn(List.of(robot(1, RobotRunStatus.IDLE), robot(2, RobotRunStatus.IDLE)));

        RobotAvailabilityResponse availability = service.getRobotAvailability();

        assertThat(availability.freeRobots()).isEqualTo(2);
        assertThat(availability.totalRobots()).isEqualTo(2);
        assertThat(availability.minFreeRobots()).isEqualTo(2);
        assertThat(availability.launchAllowed()).isTrue();
    }

    @Test
    void getRobotAvailabilityReportsNotAllowedWhenNotEnoughFreeRobots() {
        // тот же снимок, который используют фронт для поллинга и сам запуск для решения — не
        // побочный эффект, не создаёт прогон, не бросает исключение
        when(robotsPort.list()).thenReturn(List.of(
                robot(1, RobotRunStatus.IDLE), robot(2, RobotRunStatus.RUNNING), robot(3, RobotRunStatus.RUNNING)));

        RobotAvailabilityResponse availability = service.getRobotAvailability();

        assertThat(availability.freeRobots()).isEqualTo(1);
        assertThat(availability.totalRobots()).isEqualTo(3);
        assertThat(availability.minFreeRobots()).isEqualTo(2);
        assertThat(availability.launchAllowed()).isFalse();
        verify(runRepository, never()).save(any());
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

    @Test
    void startRunHandsFinishedRunToCompletionHandlerAfterTheEngine() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        when(runRepository.save(any())).thenReturn(run(100L, 1L));

        service.startRun(1L, "tester");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(engine, completionHandler);
        order.verify(engine).runScenario(100L, null);
        order.verify(completionHandler).onRunFinished(100L);
    }

    @Test
    void startRunStillBuildsTheReportWhenTheEngineThrows() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.of(scenario("My Scenario")));
        when(runRepository.save(any())).thenReturn(run(100L, 1L));
        org.mockito.Mockito.doThrow(new IllegalStateException("engine down")).when(engine).runScenario(100L, null);

        assertThatThrownBy(() -> service.startRun(1L, "tester")).isInstanceOf(IllegalStateException.class);

        verify(completionHandler).onRunFinished(100L);
    }

    @Test
    void listRunsWithoutFilterSortsByStartedAtDescNullsFirstThenIdDesc() {
        ScenarioRun first = new ScenarioRun(5L, "alice", null, "Scenario A");
        setId(first, 7L);
        Page<ScenarioRun> page = new PageImpl<>(List.of(first), PageRequest.of(1, 10), 11);
        when(runRepository.findAll(any(Pageable.class))).thenReturn(page);

        PageResponse<RunSummaryResponse> result = service.listRuns(null, 1, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(runRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        Sort.Order startedAt = pageable.getValue().getSort().getOrderFor("startedAt");
        assertThat(startedAt.getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(startedAt.getNullHandling()).isEqualTo(Sort.NullHandling.NULLS_FIRST);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(result.totalElements()).isEqualTo(11);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.content()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(7L);
            assertThat(row.scenarioName()).isEqualTo("Scenario A");
            assertThat(row.triggeredBy()).isEqualTo("alice");
            assertThat(row.status()).isEqualTo(RunStatus.PENDING);
        });
    }

    @Test
    void listRunsFiltersByScenarioWhenScenarioIdGiven() {
        when(runRepository.findByScenarioId(eq(5L), any(Pageable.class))).thenReturn(Page.empty());

        PageResponse<RunSummaryResponse> result = service.listRuns(5L, 0, 20);

        assertThat(result.content()).isEmpty();
        verify(runRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listRunsRejectsNegativePageAndOutOfRangeSize() {
        assertThatThrownBy(() -> service.listRuns(null, -1, 20)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.listRuns(null, 0, 0)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.listRuns(null, 0, 101)).isInstanceOf(InvalidRequestException.class);
        verify(runRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void getRunExposesTriggeredBy() {
        when(runRepository.findById(100L)).thenReturn(Optional.of(run(100L, 1L)));
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of());

        assertThat(service.getRun(100L).triggeredBy()).isEqualTo("tester");
    }

    private ScenarioRun run(Long id, Long scenarioId) {
        ScenarioRun run = new ScenarioRun(scenarioId, "tester");
        setId(run, id);
        return run;
    }

    private TestScenario scenario(String name) {
        return new TestScenario(name, null);
    }

    private RobotDto robot(int id, RobotRunStatus status) {
        return new RobotDto(id, "robot-" + id, status);
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
