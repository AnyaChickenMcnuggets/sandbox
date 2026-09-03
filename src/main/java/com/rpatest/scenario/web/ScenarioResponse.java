package com.rpatest.scenario.web;

import java.time.OffsetDateTime;
import java.util.List;

public record ScenarioResponse(
        Long id,
        String name,
        String description,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<StepResponse> steps) {
}
