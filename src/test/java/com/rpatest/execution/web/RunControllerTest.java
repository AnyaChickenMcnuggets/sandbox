package com.rpatest.execution.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpatest.auth.service.JwtService;
import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.GlobalExceptionHandler;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.service.ExecutionService;
import com.rpatest.execution.service.QueueAuditService;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.orchestrator.exception.OrchestratorAuthException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

// addFilters=false: тестирует HTTP-маппинг/сериализацию контроллера, не матрицу доступа (та — в
// SecurityConfigAuthorizationTest); @WithMockUser нужен для RunController.run(), который берёт
// triggeredBy из Authentication — без него getAuthentication() вернул бы null (NPE), т.к. без
// фильтров SecurityContext иначе не заполняется.
@WebMvcTest(controllers = RunController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(username = "tester")
@Import(GlobalExceptionHandler.class)
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private QueueAuditService queueAuditService;

    // JwtAuthenticationFilter (Filter-бин) попадает в @WebMvcTest slice даже при addFilters=false
    @MockBean
    private JwtService jwtService;

    @MockBean
    private com.rpatest.auth.web.AuthCookies authCookies;

    @Test
    void runReturnsAcceptedWithPendingRun() throws Exception {
        RunResponse response = new RunResponse(1L, 5L, "Test Scenario", RunStatus.PENDING, null, null, null, List.of());
        when(executionService.startRun(eq(5L), any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.scenarioName").value("Test Scenario"));
    }

    @Test
    void runDerivesTriggeredByFromAuthenticatedPrincipalNotRequestBody() throws Exception {
        RunResponse response = new RunResponse(1L, 5L, "Test Scenario", RunStatus.PENDING, null, null, null, List.of());
        when(executionService.startRun(eq(5L), eq("tester"), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/scenarios/5/run")).andExpect(status().isAccepted());

        org.mockito.Mockito.verify(executionService).startRun(5L, "tester", null);
    }

    @Test
    void getRunReturns404WhenMissing() throws Exception {
        when(executionService.getRun(99L)).thenThrow(new NotFoundException("не найден"));

        mockMvc.perform(get("/api/v1/runs/99")).andExpect(status().isNotFound());
    }

    @Test
    void stopReturnsStoppedRun() throws Exception {
        RunResponse response = new RunResponse(1L, 5L, "Test Scenario", RunStatus.STOPPED, null, null, null, List.of());
        when(executionService.stopRun(1L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/runs/1/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }

    @Test
    void runReturnsConflictWhenServiceReportsConflict() throws Exception {
        when(executionService.startRun(eq(5L), any(), any())).thenThrow(new ConflictException("уже выполняется"));

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void runReturnsBadGatewayOnOrchestratorApiError() throws Exception {
        when(executionService.startRun(eq(5L), any(), any())).thenThrow(new OrchestratorApiException("недоступен"));

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ORCHESTRATOR_API_ERROR"));
    }

    @Test
    void queueItemsPassesNaturalKeyFilterThrough() throws Exception {
        UUID itemId = UUID.randomUUID();
        QueueItemResponse item = new QueueItemResponse(itemId, "tx-1", "value", null, "SUCCESS", "ok");
        when(queueAuditService.auditQueueItems(1L, 2L, 0, 100, "tx-1", true)).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/runs/1/steps/2/queue-items")
                        .param("naturalKey", "tx-1")
                        .param("naturalKeyPart", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].naturalKey").value("tx-1"));
    }

    @Test
    void runReturnsBadGatewayOnOrchestratorAuthError() throws Exception {
        when(executionService.startRun(eq(5L), any(), any())).thenThrow(new OrchestratorAuthException("неверные учётные данные"));

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ORCHESTRATOR_AUTH_FAILED"));
    }
}
