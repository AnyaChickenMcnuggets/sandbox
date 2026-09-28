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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

/**
 * Обходит DAG шагов сценария, исполняя независимые ветки параллельно (fan-out) и синхронизируя
 * узлы с несколькими родителями (fan-in) — такой узел выполняется один раз, после того как все его
 * родители, относящиеся к текущему прогону, завершились успешно.
 */
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
        runScenario(runId, null);
    }

    /**
     * @param startStepId если задан — обход DAG начинается с этого шага (единственный "корень" для
     *                    данного прогона) вместо обычных корней сценария. Шаги, до которых обход не
     *                    дойдёт (в том числе родители узла с fan-in, недостижимые из точки старта),
     *                    остаются {@code PENDING} — так же, как шаги, пропущенные из-за падения
     *                    предка (см. {@code runStep}).
     */
    public void runScenario(Long runId, Long startStepId) {
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
            stepRunRepository.saveAll(steps.stream()
                    .map(s -> new StepRun(runId, s.getId(), s.getName(), s.getType()))
                    .toList());

            List<Long> stepIds = steps.stream().map(ScenarioStep::getId).toList();
            List<ScenarioStepEdge> edges = stepIds.isEmpty() ? List.of() : edgeRepository.findByStepIds(stepIds);

            Map<Long, List<Long>> outgoing = new HashMap<>();
            Map<Long, List<Long>> incoming = new HashMap<>();
            Set<Long> hasIncoming = new HashSet<>();
            for (ScenarioStepEdge edge : edges) {
                outgoing.computeIfAbsent(edge.getFromStepId(), k -> new ArrayList<>()).add(edge.getToStepId());
                incoming.computeIfAbsent(edge.getToStepId(), k -> new ArrayList<>()).add(edge.getFromStepId());
                hasIncoming.add(edge.getToStepId());
            }

            List<ScenarioStep> roots;
            if (startStepId != null) {
                ScenarioStep startStep = stepsById.get(startStepId);
                if (startStep == null) {
                    throw new StepExecutionException(
                            "Шаг " + startStepId + " не найден в сценарии " + run.getScenarioId());
                }
                roots = List.of(startStep);
                log.info("Прогон {}: запуск начат вручную с шага '{}' (id={}), а не с корня DAG",
                        runId, startStep.getName(), startStepId);
            } else {
                roots = steps.stream().filter(s -> !hasIncoming.contains(s.getId())).toList();
            }
            log.info("Прогон {}: {} шаг(ов) всего, {} корневых: {}", runId, steps.size(), roots.size(),
                    roots.stream().map(ScenarioStep::getName).toList());

            // Шаги, недостижимые из корней этого прогона (например, ветки "до" startStepId), не
            // считаются относящимися к прогону — родитель fan-in-узла из этого множества не должен
            // блокировать узел ожиданием, потому что в этом прогоне он в принципе не будет исполнен.
            Set<Long> reachable = computeReachable(roots, outgoing);

            Map<Long, CompletableFuture<RunStatus>> futuresByStepId = new HashMap<>();
            CompletableFuture<?>[] allFutures = reachable.stream()
                    .map(id -> getOrCreateFuture(id, run, stepsById, incoming, reachable, futuresByStepId))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(allFutures).join();

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

    private Set<Long> computeReachable(List<ScenarioStep> roots, Map<Long, List<Long>> outgoing) {
        Set<Long> visited = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        roots.forEach(r -> queue.add(r.getId()));
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (!visited.add(id)) {
                continue;
            }
            for (Long next : outgoing.getOrDefault(id, List.of())) {
                if (!visited.contains(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    /**
     * Пулл-модель вместо push-рекурсии от родителя к потомку: каждый узел сам вычисляет своё
     * будущее как зависимость от будущих СВОИХ родителей (только тех, что входят в {@code
     * reachable} — см. {@code runScenario}), а не наоборот. Это даёт fan-in "бесплатно" —
     * {@code futuresByStepId} мемоизирует по {@code stepId}, так что узел с несколькими входящими
     * рёбрами получает ровно одно будущее независимо от того, сколько родителей на него ссылаются,
     * и это будущее ждёт ВСЕХ релевантных родителей, а не срабатывает на первом из них. Не блокирует
     * поток на {@code .join()} в ожидании родителей — {@code .join()} внутри {@code
     * thenComposeAsync} вызывается только на уже завершённых (см. {@code allOf(...)} перед ним)
     * будущих, то есть не ждёт, а мгновенно читает готовый результат. Сохраняет то же свойство, ради
     * которого раньше был правлен дедлок движка (см. Sprint 14 в {@code roadmap.md}): продолжение
     * планируется на пуле по готовности, ни один поток не занимается ожиданием.
     */
    private CompletableFuture<RunStatus> getOrCreateFuture(
            Long stepId, ScenarioRun run, Map<Long, ScenarioStep> stepsById, Map<Long, List<Long>> incoming,
            Set<Long> reachable, Map<Long, CompletableFuture<RunStatus>> futuresByStepId) {
        CompletableFuture<RunStatus> existing = futuresByStepId.get(stepId);
        if (existing != null) {
            return existing;
        }

        ScenarioStep step = stepsById.get(stepId);
        List<Long> relevantParents = incoming.getOrDefault(stepId, List.of()).stream()
                .filter(reachable::contains)
                .toList();

        CompletableFuture<RunStatus> future;
        if (relevantParents.isEmpty()) {
            future = CompletableFuture.supplyAsync(() -> runStep(run, step), executor);
        } else {
            List<CompletableFuture<RunStatus>> parentFutures = relevantParents.stream()
                    .map(parentId -> getOrCreateFuture(parentId, run, stepsById, incoming, reachable, futuresByStepId))
                    .toList();
            future = CompletableFuture.allOf(parentFutures.toArray(CompletableFuture[]::new))
                    .thenComposeAsync(v -> {
                        boolean allParentsSucceeded = parentFutures.stream()
                                .allMatch(f -> f.join() == RunStatus.SUCCEEDED);
                        if (!allParentsSucceeded) {
                            log.info("Прогон {}: шаг '{}' (id={}) не запущен — не все предшественники "
                                            + "(fan-in) этого прогона завершились успешно, шаг остаётся PENDING",
                                    run.getId(), step.getName(), stepId);
                            return CompletableFuture.completedFuture(RunStatus.FAILED);
                        }
                        return CompletableFuture.supplyAsync(() -> runStep(run, step), executor);
                    }, executor);
        }
        futuresByStepId.put(stepId, future);
        return future;
    }

    private RunStatus runStep(ScenarioRun run, ScenarioStep step) {
        StepRun stepRun = stepRunRepository.findByScenarioRunIdAndStepId(run.getId(), step.getId())
                .orElseGet(() -> new StepRun(run.getId(), step.getId(), step.getName(), step.getType()));
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
