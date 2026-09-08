package com.rpatest.execution.web;

import com.rpatest.execution.domain.RunStatus;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.OffsetDateTime;
import java.util.UUID;

/** @param stepName денормализовано на {@code StepRun} при создании (см. {@code
 *                  ScenarioExecutionEngine}) — переживает удаление или пересоздание
 *                  {@code scenario_step} (PUT /scenarios/{id} пересоздаёт шаги заново); {@code
 *                  stepId} в этом случае может указывать на уже несуществующий шаг.
 * @param stepType см. {@code stepName} — то же самое денормализовано и для типа шага. */
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
        String errorMessage,
        boolean orchestratorQueueOwned) {
}
