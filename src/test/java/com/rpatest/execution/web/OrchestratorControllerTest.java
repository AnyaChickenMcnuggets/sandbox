package com.rpatest.execution.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpatest.auth.service.JwtService;
import com.rpatest.execution.service.ExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

// addFilters=false: тестирует HTTP-маппинг/сериализацию, не матрицу доступа (см. SecurityConfigAuthorizationTest)
@WebMvcTest(controllers = OrchestratorController.class)
@AutoConfigureMockMvc(addFilters = false)
class OrchestratorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExecutionService executionService;

    // JwtAuthenticationFilter (Filter-бин) попадает в @WebMvcTest slice даже при addFilters=false
    @MockBean
    private JwtService jwtService;

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
