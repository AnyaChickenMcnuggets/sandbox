package com.rpatest.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.auth.domain.AppUser;
import com.rpatest.auth.domain.Role;
import com.rpatest.auth.service.AppUserService;
import com.rpatest.auth.service.JwtService;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.service.ExecutionService;
import com.rpatest.execution.service.QueueAuditService;
import com.rpatest.execution.web.RunController;
import com.rpatest.execution.web.RunResponse;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.service.ScenarioService;
import com.rpatest.scenario.web.ScenarioController;
import com.rpatest.scenario.web.ScenarioRequest;
import com.rpatest.scenario.web.ScenarioResponse;
import com.rpatest.scenario.web.StepRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Единственное место, проверяющее саму матрицу доступа ролей (см. {@code SecurityConfig}) — какая
 * роль что может, не HTTP-маппинг конкретных контроллеров (это уже покрыто их собственными
 * *ControllerTest с {@code addFilters=false}). Реальный {@code SecurityConfig} импортирован
 * намеренно, фильтры НЕ отключены.
 */
@WebMvcTest(controllers = {RunController.class, ScenarioController.class, com.rpatest.auth.web.AdminUserController.class})
@Import(SecurityConfig.class)
class SecurityConfigAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private QueueAuditService queueAuditService;

    @MockBean
    private ScenarioService scenarioService;

    @MockBean
    private AppUserService appUserService;

    @MockBean
    private JwtService jwtService;

    @Test
    void unauthenticatedRequestIsRejectedWithUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/scenarios")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanReadScenarios() throws Exception {
        when(scenarioService.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/scenarios")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotCreateScenario() throws Exception {
        mockMvc.perform(post("/api/v1/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validScenarioRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCanCreateScenario() throws Exception {
        when(scenarioService.create(any())).thenReturn(
                new ScenarioResponse(1L, "s1", null, OffsetDateTime.now(), OffsetDateTime.now(), List.of()));

        mockMvc.perform(post("/api/v1/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validScenarioRequest())))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotDeleteScenario() throws Exception {
        mockMvc.perform(delete("/api/v1/scenarios/1")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanDeleteScenario() throws Exception {
        mockMvc.perform(delete("/api/v1/scenarios/1")).andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotStartRun() throws Exception {
        mockMvc.perform(post("/api/v1/scenarios/5/run")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCanStartRun() throws Exception {
        when(executionService.startRun(org.mockito.ArgumentMatchers.eq(5L), any(), any())).thenReturn(
                new RunResponse(1L, 5L, "s", RunStatus.PENDING, null, null, null, List.of()));

        mockMvc.perform(post("/api/v1/scenarios/5/run")).andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotAccessAdminUsers() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotAccessAdminUsers() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessAdminUsers() throws Exception {
        when(appUserService.list()).thenReturn(List.of(new AppUser("alice", "hash", Role.VIEWER)));

        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isOk());
    }

    private ScenarioRequest validScenarioRequest() {
        return new ScenarioRequest("s1", "desc", List.of(
                new StepRequest("job1", ScenarioStepType.JOB, "Job 1", Map.of("rpaProjectId", 1), List.of())));
    }
}
