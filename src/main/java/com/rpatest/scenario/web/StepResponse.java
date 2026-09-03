package com.rpatest.scenario.web;

import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.List;
import java.util.Map;

public record StepResponse(
        Long id, ScenarioStepType type, String name, Map<String, Object> config, List<Long> nextStepIds) {
}
