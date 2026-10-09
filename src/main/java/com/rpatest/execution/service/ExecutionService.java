package com.rpatest.execution.service;

import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.PageResponse;
import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.ScenarioExecutionEngine;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.report.RunCompletionHandler;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.RobotAvailabilityResponse;
import com.rpatest.execution.web.RunResponse;
import com.rpatest.execution.web.RunSummaryResponse;
import com.rpatest.execution.web.StepRunResponse;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.RobotsPort;
import com.rpatest.orchestrator.dto.RobotDto;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.TestScenario;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import com.rpatest.scenario.repository.TestScenarioRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionService {

    static final int MAX_PAGE_SIZE = 100;

    private final TestScenarioRepository scenarioRepository;
    private final ScenarioRunRepository runRepository;
    private final StepRunRepository stepRunRepository;
    private final ScenarioStepRepository scenarioStepRepository;
    private final ScenarioExecutionEngine engine;
    private final AssignmentsPort assignmentsPort;
    private final RobotsPort robotsPort;
    private final OrchestratorProperties orchestratorProperties;
    private final RunCompletionHandler completionHandler;
    private final Executor executor;

    public ExecutionService(
            TestScenarioRepository scenarioRepository,
            ScenarioRunRepository runRepository,
            StepRunRepository stepRunRepository,
            ScenarioStepRepository scenarioStepRepository,
            ScenarioExecutionEngine engine,
            AssignmentsPort assignmentsPort,
            RobotsPort robotsPort,
            OrchestratorProperties orchestratorProperties,
            RunCompletionHandler completionHandler,
            @Qualifier("scenarioExecutionExecutor") Executor executor) {
        this.scenarioRepository = scenarioRepository;
        this.runRepository = runRepository;
        this.stepRunRepository = stepRunRepository;
        this.scenarioStepRepository = scenarioStepRepository;
        this.engine = engine;
        this.assignmentsPort = assignmentsPort;
        this.robotsPort = robotsPort;
        this.orchestratorProperties = orchestratorProperties;
        this.completionHandler = completionHandler;
        this.executor = executor;
    }

    @Transactional
    public RunResponse startRun(Long scenarioId, String triggeredBy) {
        return startRun(scenarioId, triggeredBy, null);
    }

    /**
     * Снимок доступности роботов для поллинга фронтом — то же самое условие, которое
     * {@link #requireEnoughFreeRobots()} проверяет непосредственно перед запуском, но без побочных
     * эффектов и без исключения: фронт может опрашивать это постоянно, чтобы держать кнопку
     * "Запустить" в актуальном заблокированном/разблокированном состоянии, не дожидаясь попытки
     * запуска и её возможного {@code 409}.
     */
    @Transactional(readOnly = true)
    public RobotAvailabilityResponse getRobotAvailability() {
        List<RobotDto> robots = robotsPort.list();
        long free = robots.stream().filter(RobotDto::isFree).count();
        int required = orchestratorProperties.getMinFreeRobots();
        return new RobotAvailabilityResponse((int) free, robots.size(), required, free >= required);
    }

    /**
     * @param startStepId если задан — прогон начинается с этого шага, а не с корней DAG (например,
     *                    чтобы перезапустить только "зависший" JOB или повторить QUEUE_CHECK, не
     *                    пересоздавая уже готовые предшествующие очереди/задания заново). Шаги
     *                    "до" него по DAG обход не затронет — они останутся PENDING, ответственность
     *                    за то, что их предпосылки (например, данные во входной очереди) уже
     *                    выполнены, лежит на вызывающем.
     */
    @Transactional
    public RunResponse startRun(Long scenarioId, String triggeredBy, Long startStepId) {
        return startRun(scenarioId, triggeredBy, startStepId, false);
    }

    /**
     * @param sendReportByMail отправить тому, кто запустил, письмо о завершении прогона (см.
     *                         {@code ReportNotifier}). Если на сервере отправка не включена - сразу
     *                         400, до создания прогона: молча не отправить письмо хуже, чем отказать.
     */
    @Transactional
    public RunResponse startRun(Long scenarioId, String triggeredBy, Long startStepId, boolean sendReportByMail) {
        TestScenario scenario = scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new NotFoundException("Сценарий не найден: " + scenarioId));
        if (sendReportByMail && !completionHandler.isMailAvailable()) {
            throw new InvalidRequestException(
                    "Отправка отчёта на почту не включена на сервере (report.notification.enabled)");
        }
        requireEnoughFreeRobots();
        if (startStepId != null) {
            ScenarioStep startStep = scenarioStepRepository.findById(startStepId)
                    .orElseThrow(() -> new NotFoundException("Шаг " + startStepId + " не найден"));
            if (!startStep.getScenarioId().equals(scenarioId)) {
                throw new InvalidRequestException(
                        "Шаг " + startStepId + " не принадлежит сценарию " + scenarioId);
            }
        }
        // Имя сценария сохраняется на момент запуска (денормализация) — история прогонов должна
        // пережить удаление сценария и не требовать отдельного GET .../scenarios/{id} на фронте.
        ScenarioRun run = runRepository.save(new ScenarioRun(scenarioId, triggeredBy, startStepId, scenario.getName()));
        Long runId = run.getId();
        executor.execute(() -> {
            try {
                engine.runScenario(runId, startStepId);
            } finally {
                completionHandler.onRunFinished(runId, sendReportByMail);
            }
        });
        return toResponse(run, List.of());
    }

    /**
     * Newest first by {@code startedAt}; runs that are still {@code PENDING} (no {@code startedAt}
     * yet) are the newest of all, so they come first ({@code nullsFirst}, stated explicitly instead of
     * relying on the DB default). {@code id} breaks ties, so paging is stable. An unknown
     * {@code scenarioId} gives an empty page, not 404: history outlives a deleted scenario.
     */
    @Transactional(readOnly = true)
    public PageResponse<RunSummaryResponse> listRuns(Long scenarioId, int page, int size) {
        if (page < 0) {
            throw new InvalidRequestException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("startedAt").nullsFirst(), Sort.Order.desc("id")));
        Page<ScenarioRun> runs = scenarioId == null
                ? runRepository.findAll(pageable)
                : runRepository.findByScenarioId(scenarioId, pageable);
        return PageResponse.of(runs, RunSummaryResponse::from);
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

    /**
     * Не позволяет ставить прогон в очередь, если на оркестраторе заведомо некому его исполнять —
     * иначе {@code JOB}-шаг просто зависает в {@code RpaProjectQueue} в ожидании робота (см.
     * {@code StatusPoller}), и об этом узнают только по таймауту через полчаса. Порог настраивается
     * ({@code orchestrator.min-free-robots}, по умолчанию 2) — проверяется независимо от того,
     * есть ли в самом сценарии/точке возобновления (см. {@code startStepId}) хотя бы один JOB-шаг:
     * это осознанно простой блок, а не анализ конкретной топологии DAG.
     */
    private void requireEnoughFreeRobots() {
        RobotAvailabilityResponse availability = getRobotAvailability();
        if (!availability.launchAllowed()) {
            throw new ConflictException("Недостаточно свободных роботов на оркестраторе для запуска: "
                    + "свободно " + availability.freeRobots() + " из " + availability.totalRobots()
                    + ", требуется минимум " + availability.minFreeRobots() + ". Попробуйте запустить сценарий позже.");
        }
    }

    private ScenarioRun findRunOrThrow(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Прогон сценария не найден: " + runId));
    }

    private RunResponse toResponse(ScenarioRun run, List<StepRun> steps) {
        Map<Long, ScenarioStep> stepsById = new HashMap<>();
        // stepId бывает null у StepRun, чей scenario_step с тех пор удалён (см. V9-миграцию) —
        // findAllById не принимает null в списке id, отфильтровываем перед запросом.
        List<Long> stepIds = steps.stream().map(StepRun::getStepId).filter(Objects::nonNull).toList();
        if (!stepIds.isEmpty()) {
            scenarioStepRepository.findAllById(stepIds).forEach(s -> stepsById.put(s.getId(), s));
        }
        List<StepRunResponse> stepResponses = steps.stream()
                // Порядок вставки StepRun (все PENDING заводятся разом при старте рана, см.
                // ScenarioExecutionEngine) не совпадает с порядком выполнения шагов в сценарии —
                // сортируем по ScenarioStep.position, той же величине, по которой движок обходит DAG.
                .sorted(Comparator.comparingInt(s -> {
                    ScenarioStep step = stepsById.get(s.getStepId());
                    return step != null ? step.getPosition() : Integer.MAX_VALUE;
                }))
                .map(s -> new StepRunResponse(
                        s.getStepId(),
                        // Денормализовано на StepRun при создании (см. ScenarioExecutionEngine) —
                        // переживает удаление/пересоздание scenario_step, в отличие от live-join.
                        s.getStepName(),
                        s.getStepType(),
                        s.getStatus(),
                        s.getDetail(),
                        s.getDetailUpdatedAt(),
                        s.getOrchestratorAssignmentId(),
                        s.getOrchestratorQueueId(),
                        s.getStartedAt(),
                        s.getFinishedAt(),
                        s.getErrorMessage(),
                        s.isOrchestratorQueueOwned()))
                .toList();
        return new RunResponse(run.getId(), run.getScenarioId(), run.getScenarioName(), run.getTriggeredBy(),
                run.getStatus(), run.getStartedAt(), run.getFinishedAt(), run.getStartStepId(), stepResponses);
    }
}
