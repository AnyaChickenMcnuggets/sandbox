package com.rpatest.execution.web;

import com.rpatest.execution.report.ReportNotifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the UI needs to know before it shows the "mail me the report" checkbox. Open to any
 * authenticated user (it is not under a guarded prefix: it leaks nothing but a boolean).
 */
@RestController
public class ReportSettingsController {

    private final ReportNotifier notifier;

    public ReportSettingsController(ReportNotifier notifier) {
        this.notifier = notifier;
    }

    public record ReportSettingsResponse(boolean mailAvailable) {
    }

    @GetMapping("/api/v1/report/settings")
    public ReportSettingsResponse settings() {
        return new ReportSettingsResponse(notifier.isAvailable());
    }
}
