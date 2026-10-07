package com.rpatest.execution.engine.config;

import java.util.Map;

/**
 * Форма JSONB-конфига ScenarioStep(type=JOB), сохраняемая через scenario API.
 * Проект указывается либо по имени ({@code rpaProjectName} — бэкенд сам ищет id через
 * {@code GET /api/RpaProjects/v3/short}), либо напрямую по id ({@code rpaProjectId}) — задан
 * должен быть ровно один из двух.
 *
 * @param timeoutSeconds таймаут ожидания реального завершения задания на роботе (null — без
 *                       ограничения по времени, см. ADR 0005; шаг можно остановить вручную)
 * @param pollIntervalSeconds интервал опроса состояния задания (null — {@code
 *                       orchestrator.polling.interval})
 */
public record JobStepConfig(
        Integer rpaProjectId,
        String rpaProjectName,
        Integer countRobots,
        Map<String, String> arguments,
        Long timeoutSeconds,
        Long pollIntervalSeconds) {

    public boolean hasProjectName() {
        return rpaProjectName != null && !rpaProjectName.isBlank();
    }

    public Map<String, String> argumentsOrEmpty() {
        return arguments == null ? Map.of() : arguments;
    }
}
