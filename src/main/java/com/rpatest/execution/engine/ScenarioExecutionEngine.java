package com.rpatest.execution.engine;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepEdge;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.repository.ScenarioStepEdgeRepository;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Обходит DAG шагов сценария, исполняя независимые ветки параллельно (fan-out). */
@Component
public class ScenarioExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ScenarioExecutionEngine.class);

    private final ScenarioStepRepository stepRepository;
    private final ScenarioStepEdgeRepository edgeRepository;
    private final ScenarioRunRepository runRepository;
    private final StepRunRepository stepRunRepository;
    private final Map<ScenarioStepType, StepExecutor> executorsByType;
    private final Executor executor;

    public ScenarioExecutionEngine(
            ScenarioStepRepository stepRepository,
            ScenarioStepEdgeRepository edgeRepository,
            ScenarioRunRepository runRepository,
            StepRunRepository stepRunRepository,
            List<StepExecutor> executors,
            @Qualifier("scenarioExecutionExecutor") Executor executor) {
        this.stepRepository = stepRepository;
        this.edgeRepository = edgeRepository;
        this.runRepository = runRepository;
        this.stepRunRepository = stepRunRepository;
        this.executorsByType = new HashMap<>();
        executors.forEach(e -> this.executorsByType.put(e.supports(), e));
        this.executor = executor;
    }

    public void runScenario(Long runId) {
        ScenarioRun run = runRepository.findById(runId)
                .orElseThrow(() -> new StepExecutionException("ScenarioRun не найден: " + runId));
        run.markRunning();
        runRepository.save(run);

        try {
            List<ScenarioStep> steps = stepRepository.findByScenarioIdOrderByPosition(run.getScenarioId());
            Map<Long, ScenarioStep> stepsById = new HashMap<>();
            steps.forEach(s -> stepsById.put(s.getId(), s));

            List<Long> stepIds = steps.stream().map(ScenarioStep::getId).toList();
            List<ScenarioStepEdge> edges = stepIds.isEmpty() ? List.of() : edgeRepository.findByStepIds(stepIds);

            Map<Long, List<Long>> outgoing = new HashMap<>();
            Set<Long> hasIncoming = new HashSet<>();
            for (ScenarioStepEdge edge : edges) {
                outgoing.computeIfAbsent(edge.getFromStepId(), k -> new ArrayList<>()).add(edge.getToStepId());
                hasIncoming.add(edge.getToStepId());
            }

            List<ScenarioStep> roots = steps.stream().filter(s -> !hasIncoming.contains(s.getId())).toList();

            CompletableFuture<?>[] rootFutures = roots.stream()
                    .map(root -> CompletableFuture.runAsync(
                            () -> executeStepRecursively(run, root, stepsById, outgoing), executor))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(rootFutures).join();

            boolean anyFailed = stepRunRepository.findByScenarioRunId(runId).stream()
                    .anyMatch(sr -> sr.getStatus() == RunStatus.FAILED);
            run.finish(anyFailed ? RunStatus.FAILED : RunStatus.SUCCEEDED);
        } catch (Exception e) {
            log.error("Прогон сценария {} завершился с ошибкой движка", runId, e);
            run.finish(RunStatus.FAILED);
        } finally {
            runRepository.save(run);
        }
    }

    private void executeStepRecursively(
            ScenarioRun run, ScenarioStep step, Map<Long, ScenarioStep> stepsById, Map<Long, List<Long>> outgoing) {
        StepRun stepRun = new StepRun(run.getId(), step.getId());
        stepRun.markRunning();
        stepRun = stepRunRepository.save(stepRun);

        StepExecutor stepExecutor = executorsByType.get(step.getType());
        try {
            stepExecutor.execute(stepRun, step);
            stepRun.markSucceeded();
        } catch (Exception e) {
            log.warn("Шаг '{}' (id={}) прогона {} завершился с ошибкой", step.getName(), step.getId(), run.getId(), e);
            stepRun.markFailed(e.getMessage());
        } finally {
            stepRunRepository.save(stepRun);
        }

        if (stepRun.getStatus() != RunStatus.SUCCEEDED) {
            return;
        }

        List<Long> nextStepIds = outgoing.getOrDefault(step.getId(), List.of());
        if (nextStepIds.isEmpty()) {
            return;
        }
        CompletableFuture<?>[] childFutures = nextStepIds.stream()
                .map(id -> CompletableFuture.runAsync(
                        () -> executeStepRecursively(run, stepsById.get(id), stepsById, outgoing), executor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(childFutures).join();
    }
}
