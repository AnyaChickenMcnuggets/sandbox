package com.rpatest.execution.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.RunReport;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.RunReportRepository;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.scenario.domain.ScenarioStepEdge;
import com.rpatest.scenario.repository.ScenarioStepEdgeRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds and stores the run report. The report is a snapshot taken when the run finishes (see
 * {@link RunCompletionHandler}), not a live view: queue contents are gone after cleanup and the
 * scenario's edges are recreated on every scenario edit, so a report computed later from live data
 * would silently change or lose information. Runs that finished before reports existed get their
 * snapshot built on first request from whatever is stored (their steps have no structured
 * {@code result}, the report says so).
 */
@Service
public class RunReportService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ScenarioRunRepository runRepository;
    private final StepRunRepository stepRunRepository;
    private final ScenarioStepEdgeRepository edgeRepository;
    private final RunReportRepository reportRepository;
    private final ObjectMapper objectMapper;

    public RunReportService(
            ScenarioRunRepository runRepository,
            StepRunRepository stepRunRepository,
            ScenarioStepEdgeRepository edgeRepository,
            RunReportRepository reportRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.stepRunRepository = stepRunRepository;
        this.edgeRepository = edgeRepository;
        this.reportRepository = reportRepository;
        this.objectMapper = objectMapper;
    }

    /** The stored snapshot of a finished run; built (and stored) on first request for legacy runs. */
    @Transactional
    public RunReportSnapshot get(Long runId) {
        ScenarioRun run = findRun(runId);
        if (!run.getStatus().isTerminal()) {
            throw new ConflictException("Прогон ещё выполняется, отчёт будет доступен после его завершения");
        }
        return reportRepository.findById(runId)
                .map(stored -> objectMapper.convertValue(stored.getSnapshot(), RunReportSnapshot.class))
                .orElseGet(() -> store(build(run)));
    }

    /** Builds a fresh snapshot from the current state of the run and replaces the stored one. */
    @Transactional
    public RunReportSnapshot rebuild(Long runId) {
        return store(build(findRun(runId)));
    }

    private RunReportSnapshot store(RunReportSnapshot snapshot) {
        reportRepository.save(new RunReport(snapshot.runId(), objectMapper.convertValue(snapshot, MAP_TYPE)));
        return snapshot;
    }

    private RunReportSnapshot build(ScenarioRun run) {
        // StepRun rows are created all at once in scenario order when the run starts, so the id
        // order is the execution-plan order.
        List<StepRun> stepRuns = stepRunRepository.findByScenarioRunId(run.getId()).stream()
                .sorted(Comparator.comparing(StepRun::getId))
                .toList();

        Map<Long, Long> stepRunIdByStepId = new HashMap<>();
        stepRuns.stream().filter(s -> s.getStepId() != null).forEach(s -> stepRunIdByStepId.put(s.getStepId(), s.getId()));
        List<RunReportSnapshot.Edge> edges = stepRunIdByStepId.isEmpty() ? List.of()
                : edgeRepository.findByStepIds(List.copyOf(stepRunIdByStepId.keySet())).stream()
                        .filter(e -> stepRunIdByStepId.containsKey(e.getFromStepId())
                                && stepRunIdByStepId.containsKey(e.getToStepId()))
                        .map(e -> toEdge(e, stepRunIdByStepId))
                        .toList();

        List<RunReportSnapshot.Step> steps = stepRuns.stream()
                .map(s -> new RunReportSnapshot.Step(s.getId(), s.getStepName(), s.getStepType(), s.getStatus(),
                        s.getStartedAt(), s.getFinishedAt(), seconds(s.getStartedAt(), s.getFinishedAt()),
                        s.getDetail(), s.getErrorMessage(), s.getResult()))
                .toList();

        return new RunReportSnapshot(run.getId(), run.getScenarioId(), run.getScenarioName(), run.getTriggeredBy(),
                run.getStatus(), run.getStartedAt(), run.getFinishedAt(), seconds(run.getStartedAt(), run.getFinishedAt()),
                run.getStartStepId(), steps, edges, OffsetDateTime.now());
    }

    private static RunReportSnapshot.Edge toEdge(ScenarioStepEdge edge, Map<Long, Long> stepRunIdByStepId) {
        return new RunReportSnapshot.Edge(stepRunIdByStepId.get(edge.getFromStepId()), stepRunIdByStepId.get(edge.getToStepId()));
    }

    private static Long seconds(OffsetDateTime from, OffsetDateTime to) {
        return from == null || to == null ? null : Duration.between(from, to).toSeconds();
    }

    private ScenarioRun findRun(Long runId) {
        return runRepository.findById(Objects.requireNonNull(runId))
                .orElseThrow(() -> new NotFoundException("Прогон сценария не найден: " + runId));
    }
}
