package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.RpaProjectVariablesPort;
import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.dto.AssignmentStatus;
import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobStepExecutorTest {

    private AssignmentsPort assignmentsPort;
    private RpaProjectVariablesPort rpaProjectVariablesPort;
    private StatusPoller statusPoller;
    private JobStepExecutor executor;

    @BeforeEach
    void setUp() {
        assignmentsPort = mock(AssignmentsPort.class);
        rpaProjectVariablesPort = mock(RpaProjectVariablesPort.class);
        statusPoller = mock(StatusPoller.class);
        executor = new JobStepExecutor(assignmentsPort, rpaProjectVariablesPort, statusPoller, new ObjectMapper());
    }

    @Test
    void supportsJobType() {
        assertThat(executor.supports()).isEqualTo(ScenarioStepType.JOB);
    }

    @Test
    void createsStartsAndRecordsAssignmentIdOnSuccess() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "My_Job_10_5", "My Job", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(statusPoller.pollUntilTerminal(42))
                .thenReturn(new AssignmentDto(42, "My_Job_10_5", "My Job", 3, AssignmentStatus.COMPLETE, null, null, null));

        executor.execute(stepRun, step);

        assertThat(stepRun.getOrchestratorAssignmentId()).isEqualTo(42);
        verify(assignmentsPort).start(42);
        verify(rpaProjectVariablesPort, never()).get(anyInt());
    }

    @Test
    void appliesMatchingArgumentsBeforeStarting() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3, "arguments", Map.of("x", "1", "unknown", "y")));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(rpaProjectVariablesPort.get(42)).thenReturn(List.of(new RpaProjectVariableDto(99, "x", "0")));
        when(statusPoller.pollUntilTerminal(42))
                .thenReturn(new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.COMPLETE, null, null, null));

        executor.execute(stepRun, step);

        verify(rpaProjectVariablesPort).update(42, List.of(new RpaProjectVariableEditByIdDto(99, "1")));
    }

    @Test
    void doesNotCallUpdateWhenNoArgumentNameMatches() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3, "arguments", Map.of("unknown", "y")));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(rpaProjectVariablesPort.get(42)).thenReturn(List.of(new RpaProjectVariableDto(99, "x", "0")));
        when(statusPoller.pollUntilTerminal(42))
                .thenReturn(new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.COMPLETE, null, null, null));

        executor.execute(stepRun, step);

        verify(rpaProjectVariablesPort, never()).update(anyInt(), any());
    }

    @Test
    void throwsWhenAssignmentEndsInError() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(statusPoller.pollUntilTerminal(42))
                .thenReturn(new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.ERROR, null, null, "boom"));

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void wrapsOrchestratorApiExceptionIntoStepExecutionException() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3));
        StepRun stepRun = new StepRun(10L, 5L);
        when(assignmentsPort.create(any())).thenThrow(new OrchestratorApiException("network error"));

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("My Job");
    }

    @Test
    void sanitizesAssignmentNameSentToOrchestrator() {
        ScenarioStep step = step(5L, "Тест Job", Map.of("rpaProjectId", 3));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "x", "y", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(statusPoller.pollUntilTerminal(42))
                .thenReturn(new AssignmentDto(42, "x", "y", 3, AssignmentStatus.COMPLETE, null, null, null));

        executor.execute(stepRun, step);

        org.mockito.ArgumentCaptor<AssignmentCreateDto> captor = org.mockito.ArgumentCaptor.forClass(AssignmentCreateDto.class);
        verify(assignmentsPort).create(captor.capture());
        assertThat(captor.getValue().name()).matches("[A-Za-z0-9_]+");
    }

    private ScenarioStep step(Long id, String name, Map<String, Object> config) {
        ScenarioStep step = new ScenarioStep(100L, ScenarioStepType.JOB, name, config, 0);
        setId(step, id);
        return step;
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
