package com.rpatest.execution.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpatest.execution.service.ExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OrchestratorController.class)
class OrchestratorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExecutionService executionService;

    @Test
    void robotsAvailabilityReturnsSnapshotFromService() throws Exception {
        when(executionService.getRobotAvailability())
                .thenReturn(new RobotAvailabilityResponse(1, 3, 2, false));

        mockMvc.perform(get("/api/v1/orchestrator/robots-availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeRobots").value(1))
                .andExpect(jsonPath("$.totalRobots").value(3))
                .andExpect(jsonPath("$.minFreeRobots").value(2))
                .andExpect(jsonPath("$.launchAllowed").value(false));
    }
}
