package com.rpatest.execution.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.RunReport;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.RunReportRepository;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.scenario.domain.ScenarioStepEdge;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.repository.ScenarioStepEdgeRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunReportServiceTest {

    private ScenarioRunRepository runRepository;
    private StepRunRepository stepRunRepository;
    private ScenarioStepEdgeRepository edgeRepository;
    private RunReportRepository reportRepository;
    private ObjectMapper objectMapper;
    private RunReportService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(ScenarioRunRepository.class);
        stepRunRepository = mock(StepRunRepository.class);
        edgeRepository = mock(ScenarioStepEdgeRepository.class);
        reportRepository = mock(RunReportRepository.class);
        objectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        service = new RunReportService(runRepository, stepRunRepository, edgeRepository, reportRepository, objectMapper);
    }

    @Test
    void getRefusesAReportForARunThatIsStillRunning() {
        when(runRepository.findById(7L)).thenReturn(Optional.of(run(7L, RunStatus.RUNNING)));

        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(ConflictException.class);
    }

    @Test
    void getThrowsNotFoundForUnknownRun() {
        when(runRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(7L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.rebuild(7L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getReturnsTheStoredSnapshotWithoutRebuildingIt() {
        RunReportSnapshot original = ReportFixtures.succeededChain();
        Map<String, Object> stored = objectMapper.convertValue(original, new TypeReference<Map<String, Object>>() { });
        when(runRepository.findById(12L)).thenReturn(Optional.of(run(12L, RunStatus.SUCCEEDED)));
        when(reportRepository.findById(12L)).thenReturn(Optional.of(new RunReport(12L, stored)));

        RunReportSnapshot result = service.get(12L);

        assertThat(result.runId()).isEqualTo(12L);
        assertThat(result.scenarioName()).isEqualTo("Сверка платежей");
        assertThat(result.finishedAt().toInstant()).isEqualTo(original.finishedAt().toInstant());
        assertThat(result.steps()).extracting(RunReportSnapshot.Step::name)
                .containsExactly("Входная очередь", "Обработка", "Проверка результата");
        assertThat(result.steps().get(1).result()).containsEntry("robotName", "robot-7").containsEntry("success", true);
        assertThat(result.edges()).containsExactlyElementsOf(original.edges());
        verify(stepRunRepository, never()).findByScenarioRunId(any());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void getBuildsAndStoresTheSnapshotOfALegacyRun() {
        when(runRepository.findById(12L)).thenReturn(Optional.of(run(12L, RunStatus.FAILED)));
        when(reportRepository.findById(12L)).thenReturn(Optional.empty());
        when(stepRunRepository.findByScenarioRunId(12L)).thenReturn(List.of(stepRun(31L, 101L, "Check")));

        RunReportSnapshot result = service.get(12L);

        assertThat(result.runId()).isEqualTo(12L);
        assertThat(result.steps()).singleElement().satisfies(s -> assertThat(s.result()).isNull());
        verify(reportRepository).save(any(RunReport.class));
    }

    @Test
    void rebuildOrdersStepsByIdMapsEdgesToStepRunIdsAndStoresIt() {
        when(runRepository.findById(12L)).thenReturn(Optional.of(run(12L, RunStatus.SUCCEEDED)));
        StepRun first = stepRun(31L, 101L, "Queue");
        StepRun second = stepRun(32L, 102L, "Job");
        StepRun deletedStep = stepRun(33L, null, "Orphan");
        first.setResult(Map.of("queueName", "q"));
        when(stepRunRepository.findByScenarioRunId(12L)).thenReturn(List.of(deletedStep, second, first));
        when(edgeRepository.findByStepIds(any())).thenReturn(List.of(
                new ScenarioStepEdge(101L, 102L), new ScenarioStepEdge(102L, 999L)));

        RunReportSnapshot result = service.rebuild(12L);

        assertThat(result.steps()).extracting(RunReportSnapshot.Step::name).containsExactly("Queue", "Job", "Orphan");
        assertThat(result.steps().get(0).result()).containsEntry("queueName", "q");
        assertThat(result.edges()).containsExactly(new RunReportSnapshot.Edge(31L, 32L));
        assertThat(result.triggeredBy()).isEqualTo("ivanov");
        assertThat(result.scenarioName()).isEqualTo("Scenario");
        ArgumentCaptor<RunReport> stored = ArgumentCaptor.forClass(RunReport.class);
        verify(reportRepository).save(stored.capture());
        assertThat(stored.getValue().getScenarioRunId()).isEqualTo(12L);
        assertThat(stored.getValue().getSnapshot()).containsKeys("runId", "steps", "edges");
    }

    @Test
    void rebuildComputesDurationsFromTimestamps() {
        when(runRepository.findById(12L)).thenReturn(Optional.of(run(12L, RunStatus.SUCCEEDED)));
        StepRun step = stepRun(31L, 101L, "Queue");
        step.markRunning();
        step.markSucceeded();
        when(stepRunRepository.findByScenarioRunId(12L)).thenReturn(List.of(step));

        RunReportSnapshot result = service.rebuild(12L);

        assertThat(result.durationSeconds()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.steps().get(0).durationSeconds()).isNotNull();
    }

    private ScenarioRun run(Long id, RunStatus status) {
        ScenarioRun run = new ScenarioRun(5L, "ivanov", null, "Scenario");
        set(run, "id", id);
        if (status == RunStatus.RUNNING) {
            run.markRunning();
        } else if (status.isTerminal()) {
            run.markRunning();
            run.finish(status);
        }
        return run;
    }

    private StepRun stepRun(Long id, Long stepId, String name) {
        StepRun stepRun = new StepRun(12L, stepId, name, ScenarioStepType.QUEUE);
        set(stepRun, "id", id);
        return stepRun;
    }

    private void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
