package com.rpatest.execution.web;

import com.rpatest.execution.domain.RunStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StepRunResponse(
        Long stepId,
        RunStatus status,
        Integer orchestratorAssignmentId,
        UUID orchestratorQueueId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorMessage) {
}
