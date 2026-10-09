package com.rpatest.execution.report;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the run report shows, frozen when the run finished. Steps are in execution-plan order
 * (scenario position), edges refer to step ids of this snapshot ({@link Step#id()} is the
 * {@code StepRun} id) because the scenario's own edges are recreated on every scenario edit.
 */
public record RunReportSnapshot(
        Long runId,
        Long scenarioId,
        String scenarioName,
        String triggeredBy,
        RunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long durationSeconds,
        Long startStepId,
        List<Step> steps,
        List<Edge> edges,
        OffsetDateTime generatedAt) {

    public record Step(
            Long id,
            String name,
            ScenarioStepType type,
            RunStatus status,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            Long durationSeconds,
            String detail,
            String errorMessage,
            Map<String, Object> result) {
    }

    public record Edge(Long from, Long to) {
    }
}
