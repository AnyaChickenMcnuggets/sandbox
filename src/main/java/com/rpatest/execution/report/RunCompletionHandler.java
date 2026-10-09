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

    /** Whether the server can send report mails at all (checked before a run that asks for one). */
    public boolean isMailAvailable() {
        return notifier.isAvailable();
    }

    /** @param sendMail the run was started with the "mail me the report" attribute */
    public void onRunFinished(Long runId, boolean sendMail) {
        try {
            RunReportSnapshot report = reportService.rebuild(runId);
            if (sendMail) {
                notifier.notifyFinished(report);
            }
        } catch (RuntimeException e) {
            log.error("Failed to build the report of run {}", runId, e);
        }
    }
}
