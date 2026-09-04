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
        log.info("Прогон {} (сценарий {}) запущен", runId, run.getScenarioId());

        try {
            List<ScenarioStep> steps = stepRepository.findByScenarioIdOrderByPosition(run.getScenarioId());
            Map<Long, ScenarioStep> stepsById = new HashMap<>();
            steps.forEach(s -> stepsById.put(s.getId(), s));

            // Заводим StepRun(PENDING) на каждый шаг сценария сразу, до начала обхода DAG — иначе
            // шаги, до которых обход ещё не дошёл (например, QUEUE_CHECK после ещё выполняющегося
            // JOB), просто отсутствуют в GET /api/v1/runs/{runId} вместо того чтобы быть видны как
            // "ещё не начался", и по ответу нельзя понять всю топологию прогона заранее.
            stepRunRepository.saveAll(steps.stream().map(s -> new StepRun(runId, s.getId())).toList());

            List<Long> stepIds = steps.stream().map(ScenarioStep::getId).toList();
            List<ScenarioStepEdge> edges = stepIds.isEmpty() ? List.of() : edgeRepository.findByStepIds(stepIds);

            Map<Long, List<Long>> outgoing = new HashMap<>();
            Set<Long> hasIncoming = new HashSet<>();
            for (ScenarioStepEdge edge : edges) {
                outgoing.computeIfAbsent(edge.getFromStepId(), k -> new ArrayList<>()).add(edge.getToStepId());
                hasIncoming.add(edge.getToStepId());
            }

            List<ScenarioStep> roots = steps.stream().filter(s -> !hasIncoming.contains(s.getId())).toList();
            log.info("Прогон {}: {} шаг(ов) всего, {} корневых: {}", runId, steps.size(), roots.size(),
                    roots.stream().map(ScenarioStep::getName).toList());

            CompletableFuture<?>[] rootFutures = roots.stream()
                    .map(root -> executeStepAsync(run, root, stepsById, outgoing))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(rootFutures).join();

            boolean anyFailed = stepRunRepository.findByScenarioRunId(runId).stream()
                    .anyMatch(sr -> sr.getStatus() == RunStatus.FAILED);
            run.finish(anyFailed ? RunStatus.FAILED : RunStatus.SUCCEEDED);
            log.info("Прогон {} завершён со статусом {}", runId, run.getStatus());
        } catch (Exception e) {
            log.error("Прогон сценария {} завершился с ошибкой движка", runId, e);
            run.finish(RunStatus.FAILED);
        } finally {
            runRepository.save(run);
        }
    }

    /**
     * Не блокирует поток на {@code .join()} в ожидании дочерних шагов — при recursion через
     * {@code runAsync(...).join()} каждый уровень цепочки навсегда занимал отдельный поток пула,
     * и на цепочке длиннее {@code corePoolSize} (см. {@code AsyncConfig}) все core-потоки
     * оказывались заблокированы в ожидании друг друга раньше, чем пул успевал вырасти до
     * {@code maxPoolSize} — {@code ThreadPoolExecutor} создаёт потоки сверх core только когда
     * очередь заполнена, а не когда все core-потоки заняты/блокированы. {@code thenComposeAsync}
     * планирует продолжение на пуле по готовности, не занимая поток ожиданием.
     */
    private CompletableFuture<Void> executeStepAsync(
            ScenarioRun run, ScenarioStep step, Map<Long, ScenarioStep> stepsById, Map<Long, List<Long>> outgoing) {
        return CompletableFuture.supplyAsync(() -> runStep(run, step), executor)
                .thenComposeAsync(status -> {
                    if (status != RunStatus.SUCCEEDED) {
                        return CompletableFuture.completedFuture(null);
                    }
                    List<Long> nextStepIds = outgoing.getOrDefault(step.getId(), List.of());
                    if (nextStepIds.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    log.info("Прогон {}: шаг '{}' запускает следующие шаги: {}", run.getId(), step.getName(),
                            nextStepIds.stream().map(id -> stepsById.get(id).getName()).toList());
                    CompletableFuture<?>[] childFutures = nextStepIds.stream()
                            .map(id -> executeStepAsync(run, stepsById.get(id), stepsById, outgoing))
                            .toArray(CompletableFuture[]::new);
                    return CompletableFuture.allOf(childFutures);
                }, executor);
    }

    private RunStatus runStep(ScenarioRun run, ScenarioStep step) {
        StepRun stepRun = stepRunRepository.findByScenarioRunIdAndStepId(run.getId(), step.getId())
                .orElseGet(() -> new StepRun(run.getId(), step.getId()));
        stepRun.markRunning();
        stepRun = stepRunRepository.save(stepRun);
        log.info("Прогон {}: шаг '{}' (id={}, тип={}) начат", run.getId(), step.getName(), step.getId(), step.getType());

        StepExecutor stepExecutor = executorsByType.get(step.getType());
        try {
            stepExecutor.execute(stepRun, step);
            stepRun.markSucceeded();
            log.info("Прогон {}: шаг '{}' (id={}) завершён успешно", run.getId(), step.getName(), step.getId());
        } catch (Exception e) {
            log.warn("Шаг '{}' (id={}) прогона {} завершился с ошибкой", step.getName(), step.getId(), run.getId(), e);
            stepRun.markFailed(describeWithCauses(e));
        } finally {
            stepRunRepository.save(stepRun);
        }
        return stepRun.getStatus();
    }

    /**
     * {@code stepRun.errorMessage} — единственное, что видит вызывающий API/UI при падении шага;
     * одного {@code e.getMessage()} часто недостаточно (например, "Не удалось выполнить проверку
     * очереди '...'" ничего не говорит о том, какой именно HTTP-вызов упал и с каким статусом) —
     * дописываем сообщения из цепочки причин.
     */
    private String describeWithCauses(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && cause != e && depth < 5) {
            sb.append(" — ").append(cause.getMessage());
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }
}
