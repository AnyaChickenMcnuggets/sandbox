package com.rpatest.execution.web;

import com.rpatest.execution.service.ExecutionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Статус оркестратора, не привязанный к конкретному сценарию/прогону. */
@RestController
@RequestMapping("/api/v1/orchestrator")
public class OrchestratorController {

    private final ExecutionService executionService;

    public OrchestratorController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Для поллинга фронтом перед показом/разблокировкой кнопки запуска сценария — то же самое
     * условие, которое {@code POST /api/v1/scenarios/{id}/run} проверит непосредственно перед
     * запуском (см. {@code ExecutionService.requireEnoughFreeRobots}), но без побочных эффектов:
     * не создаёт прогон, можно опрашивать сколько угодно часто.
     */
    @GetMapping("/robots-availability")
    public RobotAvailabilityResponse robotsAvailability() {
        return executionService.getRobotAvailability();
    }
}
