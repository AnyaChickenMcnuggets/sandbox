package com.rpatest.execution.web;

import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.execution.report.RunReportHtmlRenderer;
import com.rpatest.execution.report.RunReportService;
import com.rpatest.execution.report.RunReportSnapshot;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RunReportController {

    /** The report is plain HTML + SVG: forbid scripts, remote content and framing outright. */
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; img-src data:; frame-ancestors 'none'";

    private final RunReportService reportService;

    public RunReportController(RunReportService reportService) {
        this.reportService = reportService;
    }

    /** {@code format=html} (default) is the human-readable page, {@code format=json} the raw snapshot. */
    @GetMapping("/api/v1/runs/{runId}/report")
    public ResponseEntity<?> report(@PathVariable Long runId, @RequestParam(defaultValue = "html") String format) {
        if (!"html".equals(format) && !"json".equals(format)) {
            throw new InvalidRequestException("format must be html or json");
        }
        RunReportSnapshot report = reportService.get(runId);
        if ("json".equals(format)) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(report);
        }
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("report-run-" + runId + ".html").build().toString())
                .body(RunReportHtmlRenderer.render(report));
    }
}
