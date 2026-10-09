package com.rpatest.execution.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpatest.auth.service.JwtService;
import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.GlobalExceptionHandler;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.report.RunReportService;
import com.rpatest.execution.report.RunReportSnapshot;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RunReportController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser
@Import(GlobalExceptionHandler.class)
class RunReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RunReportService reportService;

    // JwtAuthenticationFilter (Filter bean) is part of every @WebMvcTest slice even with addFilters=false
    @MockBean
    private JwtService jwtService;

    @MockBean
    private com.rpatest.auth.web.AuthCookies authCookies;

    private RunReportSnapshot snapshot() {
        OffsetDateTime now = OffsetDateTime.now();
        return new RunReportSnapshot(12L, 5L, "Сверка", "ivanov", RunStatus.SUCCEEDED, now, now, 0L, null,
                List.of(), List.of(), now);
    }

    @Test
    void servesTheHumanReadableHtmlByDefaultWithAStrictContentSecurityPolicy() throws Exception {
        when(reportService.get(12L)).thenReturn(snapshot());

        mockMvc.perform(get("/api/v1/runs/12/report"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Content-Disposition", containsString("report-run-12.html")))
                .andExpect(content().string(containsString("Отчёт о тестировании")));
    }

    @Test
    void servesTheRawSnapshotAsJsonOnRequest() throws Exception {
        when(reportService.get(12L)).thenReturn(snapshot());

        mockMvc.perform(get("/api/v1/runs/12/report").param("format", "json"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.runId").value(12))
                .andExpect(jsonPath("$.scenarioName").value("Сверка"))
                .andExpect(jsonPath("$.triggeredBy").value("ivanov"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void rejectsAnUnknownFormat() throws Exception {
        mockMvc.perform(get("/api/v1/runs/12/report").param("format", "pdf"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returns404ForUnknownRunAndConflictWhileItRuns() throws Exception {
        when(reportService.get(99L)).thenThrow(new NotFoundException("no run"));
        when(reportService.get(7L)).thenThrow(new ConflictException("still running"));

        mockMvc.perform(get("/api/v1/runs/99/report")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/runs/7/report")).andExpect(status().isConflict());
    }
}
