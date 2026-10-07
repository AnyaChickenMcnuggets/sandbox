package com.rpatest.execution.web;

import com.rpatest.execution.domain.RunStatus;
import java.time.OffsetDateTime;
import java.util.List;

/** @param scenarioName денормализованное имя сценария на момент запуска (см. {@code ScenarioRun}) —
 *                      не требует отдельного GET /api/v1/scenarios/{scenarioId} и переживает
 *                      удаление сценария; для старых прогонов, созданных до этого поля, может
 *                      быть null, если исходный сценарий к моменту миграции уже был удалён.
 *  @param triggeredBy  логин пользователя, запустившего прогон — берётся из аутентификации на
 *                      момент запуска, не из тела запроса (см. {@code RunController.run}). */
public record RunResponse(
        Long id,
        Long scenarioId,
        String scenarioName,
        String triggeredBy,
        RunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long startStepId,
        List<StepRunResponse> steps) {
}
