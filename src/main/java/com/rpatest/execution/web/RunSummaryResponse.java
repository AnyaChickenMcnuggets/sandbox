package com.rpatest.execution.web;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.domain.ScenarioRun;
import java.time.OffsetDateTime;

/**
 * Row of {@code GET /api/v1/runs}: the same header fields as {@link RunResponse}, without
 * {@code steps} (a list must not load step runs for every row; the details are one
 * {@code GET /api/v1/runs/{id}} away).
 */
public record RunSummaryResponse(
        Long id,
        Long scenarioId,
        String scenarioName,
        String triggeredBy,
        RunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long startStepId) {

    public static RunSummaryResponse from(ScenarioRun run) {
        return new RunSummaryResponse(run.getId(), run.getScenarioId(), run.getScenarioName(), run.getTriggeredBy(),
                run.getStatus(), run.getStartedAt(), run.getFinishedAt(), run.getStartStepId());
    }
}
