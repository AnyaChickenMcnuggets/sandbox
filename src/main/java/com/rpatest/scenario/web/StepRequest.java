package com.rpatest.scenario.web;

import com.rpatest.scenario.domain.ScenarioStepType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record StepRequest(
        @NotBlank String localId,
        @NotNull ScenarioStepType type,
        @NotBlank String name,
        @NotNull Map<String, Object> config,
        List<String> nextLocalIds) {

    public List<String> nextLocalIdsOrEmpty() {
        return nextLocalIds == null ? List.of() : nextLocalIds;
    }
}
