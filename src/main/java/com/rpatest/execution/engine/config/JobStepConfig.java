package com.rpatest.execution.engine.config;

import java.util.Map;

/**
 * Форма JSONB-конфига ScenarioStep(type=JOB), сохраняемая через scenario API.
 * Проект указывается либо по имени ({@code rpaProjectName} — бэкенд сам ищет id через
 * {@code GET /api/RpaProjects/v3/short}), либо напрямую по id ({@code rpaProjectId}) — задан
 * должен быть ровно один из двух.
 */
public record JobStepConfig(Integer rpaProjectId, String rpaProjectName, Integer countRobots, Map<String, String> arguments) {

    public boolean hasProjectName() {
        return rpaProjectName != null && !rpaProjectName.isBlank();
    }

    public Map<String, String> argumentsOrEmpty() {
        return arguments == null ? Map.of() : arguments;
    }
}
