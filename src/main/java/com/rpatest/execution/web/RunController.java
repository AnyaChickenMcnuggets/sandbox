package com.rpatest.execution.web;

import com.rpatest.common.web.PageResponse;
import com.rpatest.execution.service.ExecutionService;
import com.rpatest.execution.service.QueueAuditService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RunController {

    private final ExecutionService executionService;
    private final QueueAuditService queueAuditService;

    public RunController(ExecutionService executionService, QueueAuditService queueAuditService) {
        this.executionService = executionService;
        this.queueAuditService = queueAuditService;
    }

    @PostMapping("/api/v1/scenarios/{scenarioId}/run")
    public ResponseEntity<RunResponse> run(@PathVariable Long scenarioId, @RequestBody(required = false) RunRequest request) {
        Long startStepId = request != null ? request.startStepId() : null;
        String triggeredBy = SecurityContextHolder.getContext().getAuthentication().getName();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(executionService.startRun(scenarioId, triggeredBy, startStepId));
    }

    @GetMapping("/api/v1/runs")
    public PageResponse<RunSummaryResponse> listRuns(
            @RequestParam(required = false) Long scenarioId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return executionService.listRuns(scenarioId, page, size);
    }

    @GetMapping("/api/v1/runs/{runId}")
    public RunResponse getRun(@PathVariable Long runId) {
        return executionService.getRun(runId);
    }

    @PostMapping("/api/v1/runs/{runId}/stop")
    public RunResponse stop(@PathVariable Long runId) {
        return executionService.stopRun(runId);
    }

    @GetMapping("/api/v1/runs/{runId}/steps/{stepId}/queue-items")
    public List<QueueItemResponse> queueItems(
            @PathVariable Long runId,
            @PathVariable Long stepId,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "100") int pageSize,
            @RequestParam(required = false) String naturalKey,
            @RequestParam(defaultValue = "false") boolean naturalKeyPart) {
        return queueAuditService.auditQueueItems(runId, stepId, pageNumber, pageSize, naturalKey, naturalKeyPart);
    }
}
