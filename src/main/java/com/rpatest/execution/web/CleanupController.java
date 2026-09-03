package com.rpatest.execution.web;

import com.rpatest.execution.service.CleanupService;
import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CleanupController {

    private final CleanupService cleanupService;

    public CleanupController(CleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @PostMapping("/api/v1/scenarios/{scenarioId}/cleanup")
    public CleanupResponse cleanup(@PathVariable Long scenarioId) {
        List<String> failures = cleanupService.cleanupLastRun(scenarioId);
        return new CleanupResponse(failures.isEmpty(), failures);
    }

    public record CleanupResponse(boolean success, List<String> failures) {
    }
}
