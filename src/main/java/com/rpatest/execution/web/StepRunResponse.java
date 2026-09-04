package com.rpatest.execution.web;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StepRunResponse(
        Long stepId,
        String stepName,
        ScenarioStepType stepType,
        RunStatus status,
        String detail,
        OffsetDateTime detailUpdatedAt,
        Integer orchestratorAssignmentId,
        UUID orchestratorQueueId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorMessage) {
}
