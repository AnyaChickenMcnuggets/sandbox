package com.rpatest.execution.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.GlobalExceptionHandler;
import com.rpatest.execution.service.CleanupService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CleanupController.class)
@Import(GlobalExceptionHandler.class)
class CleanupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CleanupService cleanupService;

    @Test
    void cleanupReturnsSuccessWhenNoFailures() throws Exception {
        when(cleanupService.cleanupLastRun(5L)).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/scenarios/5/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.failures").isEmpty());
    }

    @Test
    void cleanupReturnsFailuresWhenSomeDeletesFailed() throws Exception {
        when(cleanupService.cleanupLastRun(5L)).thenReturn(List.of("assignment 42: boom"));

        mockMvc.perform(post("/api/v1/scenarios/5/cleanup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.failures[0]").value("assignment 42: boom"));
    }

    @Test
    void cleanupReturns404WhenScenarioHasNoRuns() throws Exception {
        when(cleanupService.cleanupLastRun(99L)).thenThrow(new NotFoundException("нет прогонов"));

        mockMvc.perform(post("/api/v1/scenarios/99/cleanup")).andExpect(status().isNotFound());
    }
}
