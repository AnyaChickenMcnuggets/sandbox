package com.rpatest.scenario.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ScenarioRequest(
        @NotBlank String name,
        String description,
        @NotEmpty @Valid List<StepRequest> steps) {
}
