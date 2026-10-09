package com.rpatest.execution.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What happens after the engine is done with a run: freeze the report, then notify. Called by
 * {@code ExecutionService} on the executor thread right after {@code engine.runScenario}, so the
 * engine itself knows nothing about reports. Never throws - a broken report must not turn a
 * finished run into an error.
 */
@Component
public class RunCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger(RunCompletionHandler.class);

    private final RunReportService reportService;
    private final ReportNotifier notifier;

    public RunCompletionHandler(RunReportService reportService, ReportNotifier notifier) {
        this.reportService = reportService;
        this.notifier = notifier;
    }

    public void onRunFinished(Long runId) {
        try {
            notifier.notifyFinished(reportService.rebuild(runId));
        } catch (RuntimeException e) {
            log.error("Failed to build the report of run {}", runId, e);
        }
    }
}
