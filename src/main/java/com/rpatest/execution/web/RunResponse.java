package com.rpatest.execution.web;

import com.rpatest.execution.domain.RunStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record RunResponse(
        Long id,
        Long scenarioId,
        RunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<StepRunResponse> steps) {
}
