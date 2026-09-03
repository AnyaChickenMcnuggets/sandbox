package com.rpatest.scenario.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.common.web.GlobalExceptionHandler;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.service.ScenarioService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ScenarioController.class)
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class ScenarioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ScenarioService scenarioService;

    @Test
    void createReturnsCreatedScenario() throws Exception {
        ScenarioRequest request = new ScenarioRequest("s1", "desc", List.of(
                new StepRequest("job1", ScenarioStepType.JOB, "Job 1", Map.of("rpaProjectId", 1), List.of())));
        ScenarioResponse response = new ScenarioResponse(1L, "s1", "desc", OffsetDateTime.now(), OffsetDateTime.now(), List.of());
        when(scenarioService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("s1"));
    }

    @Test
    void createRejectsRequestWithoutSteps() throws Exception {
        ScenarioRequest invalid = new ScenarioRequest("s1", "desc", List.of());

        mockMvc.perform(post("/api/v1/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturns404WhenScenarioMissing() throws Exception {
        when(scenarioService.get(eq(99L))).thenThrow(new NotFoundException("Сценарий не найден: 99"));

        mockMvc.perform(get("/api/v1/scenarios/99")).andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/scenarios/1")).andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404WhenScenarioMissing() throws Exception {
        doThrow(new NotFoundException("не найден")).when(scenarioService).delete(99L);

        mockMvc.perform(delete("/api/v1/scenarios/99")).andExpect(status().isNotFound());
    }
}
