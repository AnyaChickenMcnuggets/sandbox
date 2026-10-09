package com.rpatest.execution.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpatest.auth.service.JwtService;
import com.rpatest.execution.report.ReportNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReportSettingsController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser
class ReportSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReportNotifier notifier;

    // JwtAuthenticationFilter (Filter bean) is part of every @WebMvcTest slice even with addFilters=false
    @MockBean
    private JwtService jwtService;

    @MockBean
    private com.rpatest.auth.web.AuthCookies authCookies;

    @Test
    void reportsWhetherMailCanBeSent() throws Exception {
        when(notifier.isAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/v1/report/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailAvailable").value(true));
    }

    @Test
    void reportsMailUnavailableWhenDisabled() throws Exception {
        when(notifier.isAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/report/settings")).andExpect(jsonPath("$.mailAvailable").value(false));
    }
}
