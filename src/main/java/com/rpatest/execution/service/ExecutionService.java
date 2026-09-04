package com.rpatest.execution.service;

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
import com.rpatest.scenario.repository.ScenarioStepRepository;
import com.rpatest.scenario.repository.TestScenarioRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionService {

    private final TestScenarioRepository scenarioRepository;
    private final ScenarioRunRepository runRepository;
    private final StepRunRepository stepRunRepository;
    private final ScenarioStepRepository scenarioStepRepository;
    private final ScenarioExecutionEngine engine;
    private final AssignmentsPort assignmentsPort;
    private final Executor executor;

    public ExecutionService(
            TestScenarioRepository scenarioRepository,
            ScenarioRunRepository runRepository,
            StepRunRepository stepRunRepository,
            ScenarioStepRepository scenarioStepRepository,
            ScenarioExecutionEngine engine,
            AssignmentsPort assignmentsPort,
            @Qualifier("scenarioExecutionExecutor") Executor executor) {
        this.scenarioRepository = scenarioRepository;
        this.runRepository = runRepository;
        this.stepRunRepository = stepRunRepository;
        this.scenarioStepRepository = scenarioStepRepository;
        this.engine = engine;
        this.assignmentsPort = assignmentsPort;
        this.executor = executor;
    }

    @Transactional
    public RunResponse startRun(Long scenarioId, String triggeredBy) {
        if (!scenarioRepository.existsById(scenarioId)) {
            throw new NotFoundException("Сценарий не найден: " + scenarioId);
        }
        ScenarioRun run = runRepository.save(new ScenarioRun(scenarioId, triggeredBy));
        Long runId = run.getId();
        executor.execute(() -> engine.runScenario(runId));
        return toResponse(run, List.of());
    }

    @Transactional(readOnly = true)
    public RunResponse getRun(Long runId) {
        ScenarioRun run = findRunOrThrow(runId);
        List<StepRun> steps = stepRunRepository.findByScenarioRunId(runId);
        return toResponse(run, steps);
    }

    @Transactional
    public RunResponse stopRun(Long runId) {
        ScenarioRun run = findRunOrThrow(runId);
        if (run.getStatus().isTerminal()) {
            return toResponse(run, stepRunRepository.findByScenarioRunId(runId));
        }
        List<StepRun> steps = stepRunRepository.findByScenarioRunId(runId);
        for (StepRun step : steps) {
            if (step.getStatus() == RunStatus.RUNNING && step.getOrchestratorAssignmentId() != null) {
                assignmentsPort.stop(step.getOrchestratorAssignmentId());
                step.markFailed("Остановлено пользователем");
                stepRunRepository.save(step);
            }
        }
        run.finish(RunStatus.STOPPED);
        runRepository.save(run);
        return toResponse(run, stepRunRepository.findByScenarioRunId(runId));
    }

    private ScenarioRun findRunOrThrow(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Прогон сценария не найден: " + runId));
    }

    private RunResponse toResponse(ScenarioRun run, List<StepRun> steps) {
        Map<Long, ScenarioStep> stepsById = new HashMap<>();
        if (!steps.isEmpty()) {
            scenarioStepRepository.findAllById(steps.stream().map(StepRun::getStepId).toList())
                    .forEach(s -> stepsById.put(s.getId(), s));
        }
        List<StepRunResponse> stepResponses = steps.stream()
                .map(s -> {
                    ScenarioStep step = stepsById.get(s.getStepId());
                    return new StepRunResponse(
                            s.getStepId(),
                            step != null ? step.getName() : null,
                            step != null ? step.getType() : null,
                            s.getStatus(),
                            s.getDetail(),
                            s.getDetailUpdatedAt(),
                            s.getOrchestratorAssignmentId(),
                            s.getOrchestratorQueueId(),
                            s.getStartedAt(),
                            s.getFinishedAt(),
                            s.getErrorMessage());
                })
                .toList();
        return new RunResponse(run.getId(), run.getScenarioId(), run.getStatus(), run.getStartedAt(), run.getFinishedAt(), stepResponses);
    }
}
