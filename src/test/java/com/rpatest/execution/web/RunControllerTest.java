package com.rpatest.execution.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rpatest.common.exception.ConflictException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.GlobalExceptionHandler;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.execution.service.ExecutionService;
import com.rpatest.execution.service.QueueAuditService;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.orchestrator.exception.OrchestratorAuthException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = RunController.class)
@Import(GlobalExceptionHandler.class)
class RunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExecutionService executionService;

    @MockBean
    private QueueAuditService queueAuditService;

    @Test
    void runReturnsAcceptedWithPendingRun() throws Exception {
        RunResponse response = new RunResponse(1L, 5L, RunStatus.PENDING, null, null, List.of());
        when(executionService.startRun(eq(5L), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getRunReturns404WhenMissing() throws Exception {
        when(executionService.getRun(99L)).thenThrow(new NotFoundException("не найден"));

        mockMvc.perform(get("/api/v1/runs/99")).andExpect(status().isNotFound());
    }

    @Test
    void stopReturnsStoppedRun() throws Exception {
        RunResponse response = new RunResponse(1L, 5L, RunStatus.STOPPED, null, null, List.of());
        when(executionService.stopRun(1L)).thenReturn(response);

        mockMvc.perform(post("/api/v1/runs/1/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"));
    }

    @Test
    void runReturnsConflictWhenServiceReportsConflict() throws Exception {
        when(executionService.startRun(eq(5L), any())).thenThrow(new ConflictException("уже выполняется"));

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void runReturnsBadGatewayOnOrchestratorApiError() throws Exception {
        when(executionService.startRun(eq(5L), any())).thenThrow(new OrchestratorApiException("недоступен"));

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ORCHESTRATOR_API_ERROR"));
    }

    @Test
    void runReturnsBadGatewayOnOrchestratorAuthError() throws Exception {
        when(executionService.startRun(eq(5L), any())).thenThrow(new OrchestratorAuthException("неверные учётные данные"));

        mockMvc.perform(post("/api/v1/scenarios/5/run"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("ORCHESTRATOR_AUTH_FAILED"));
    }
}
