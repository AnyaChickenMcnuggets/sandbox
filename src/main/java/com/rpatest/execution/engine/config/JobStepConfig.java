package com.rpatest.execution.engine.config;

import java.util.Map;

/** Форма JSONB-конфига ScenarioStep(type=JOB), сохраняемая через scenario API. */
public record JobStepConfig(int rpaProjectId, Integer countRobots, Map<String, String> arguments) {

    public Map<String, String> argumentsOrEmpty() {
        return arguments == null ? Map.of() : arguments;
    }
}
