package com.rpatest.execution.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class RunCompletionHandlerTest {

    private final RunReportService reportService = mock(RunReportService.class);
    private final ReportNotifier notifier = mock(ReportNotifier.class);
    private final RunCompletionHandler handler = new RunCompletionHandler(reportService, notifier);

    @Test
    void buildsTheReportThenNotifiesWithIt() {
        RunReportSnapshot report = ReportFixtures.succeededChain();
        when(reportService.rebuild(12L)).thenReturn(report);

        handler.onRunFinished(12L);

        verify(notifier).notifyFinished(report);
    }

    @Test
    void aBrokenReportNeverPropagatesAndSendsNothing() {
        when(reportService.rebuild(12L)).thenThrow(new IllegalStateException("db down"));

        handler.onRunFinished(12L);

        verify(notifier, never()).notifyFinished(any());
    }

    @Test
    void aBrokenNotifierNeverPropagates() {
        when(reportService.rebuild(12L)).thenReturn(ReportFixtures.succeededChain());
        doThrow(new IllegalStateException("boom")).when(notifier).notifyFinished(any());

        handler.onRunFinished(12L);
    }
}
