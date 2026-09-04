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
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.RpaProjectQueuePort;
import com.rpatest.orchestrator.client.RpaProjectVariablesPort;
import com.rpatest.orchestrator.client.RpaProjectsPort;
import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.dto.AssignmentStatus;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import com.rpatest.orchestrator.dto.RpaProjectShortDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JobStepExecutorTest {

    private AssignmentsPort assignmentsPort;
    private RpaProjectsPort rpaProjectsPort;
    private RpaProjectVariablesPort rpaProjectVariablesPort;
    private RpaProjectQueuePort rpaProjectQueuePort;
    private StatusPoller statusPoller;
    private JobStepExecutor executor;

    @BeforeEach
    void setUp() {
        assignmentsPort = mock(AssignmentsPort.class);
        rpaProjectsPort = mock(RpaProjectsPort.class);
        rpaProjectVariablesPort = mock(RpaProjectVariablesPort.class);
        rpaProjectQueuePort = mock(RpaProjectQueuePort.class);
        statusPoller = mock(StatusPoller.class);
        StepProgressReporter progressReporter = new StepProgressReporter(mock(StepRunRepository.class));
        executor = new JobStepExecutor(assignmentsPort, rpaProjectsPort, rpaProjectVariablesPort,
                rpaProjectQueuePort, statusPoller, progressReporter, new ObjectMapper());
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
        when(statusPoller.pollUntilTerminal(stepRun, 42)).thenReturn(successfulLaunch(42));

        executor.execute(stepRun, step);

        assertThat(stepRun.getOrchestratorAssignmentId()).isEqualTo(42);
        verify(assignmentsPort).start(42);
        verify(rpaProjectVariablesPort, never()).get(anyInt());
    }

    @Test
    void resolvesProjectIdByNameWhenProjectNameProvided() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectName", "Invoice Processor"));
        StepRun stepRun = new StepRun(10L, 5L);
        when(rpaProjectsPort.findByName("Invoice Processor"))
                .thenReturn(Optional.of(new RpaProjectShortDto(7, "Invoice Processor", null, null, true)));
        AssignmentDto created = new AssignmentDto(42, "job", "My Job", 7, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(statusPoller.pollUntilTerminal(stepRun, 42)).thenReturn(successfulLaunch(42));

        executor.execute(stepRun, step);

        org.mockito.ArgumentCaptor<AssignmentCreateDto> captor = org.mockito.ArgumentCaptor.forClass(AssignmentCreateDto.class);
        verify(assignmentsPort).create(captor.capture());
        assertThat(captor.getValue().rpaProjectId()).isEqualTo(7);
    }

    @Test
    void throwsWhenProjectNameNotFound() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectName", "Unknown Project"));
        StepRun stepRun = new StepRun(10L, 5L);
        when(rpaProjectsPort.findByName("Unknown Project")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("Unknown Project");
    }

    @Test
    void throwsWhenNeitherProjectNameNorIdProvided() {
        ScenarioStep step = step(5L, "My Job", Map.of());
        StepRun stepRun = new StepRun(10L, 5L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    @Test
    void appliesMatchingArgumentsBeforeStarting() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3, "arguments", Map.of("x", "1", "unknown", "y")));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(rpaProjectVariablesPort.get(42)).thenReturn(List.of(new RpaProjectVariableDto(99, "x", "0")));
        when(statusPoller.pollUntilTerminal(stepRun, 42)).thenReturn(successfulLaunch(42));

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
        when(statusPoller.pollUntilTerminal(stepRun, 42)).thenReturn(successfulLaunch(42));

        executor.execute(stepRun, step);

        verify(rpaProjectVariablesPort, never()).update(anyInt(), any());
    }

    @Test
    void throwsWhenLaunchEndsInFailure() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(statusPoller.pollUntilTerminal(stepRun, 42)).thenReturn(failedLaunch(42, "robot-1"));
        when(rpaProjectQueuePort.findByAssignment(42))
                .thenReturn(List.of(new QueueItemProjectDto(1, 42, "boom", "robot-1", LocalDateTime.now(), LocalDateTime.now())));

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("robot-1")
                .hasMessageContaining("boom");
    }

    @Test
    void throwsWhenLaunchFailsEvenWithoutQueueErrorDetails() {
        ScenarioStep step = step(5L, "My Job", Map.of("rpaProjectId", 3));
        StepRun stepRun = new StepRun(10L, 5L);
        AssignmentDto created = new AssignmentDto(42, "job", "My Job", 3, AssignmentStatus.NEW, null, null, null);
        when(assignmentsPort.create(any())).thenReturn(created);
        when(statusPoller.pollUntilTerminal(stepRun, 42)).thenReturn(failedLaunch(42, "robot-1"));
        when(rpaProjectQueuePort.findByAssignment(42)).thenReturn(List.of());

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("robot-1");
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
        when(statusPoller.pollUntilTerminal(stepRun, 42)).thenReturn(successfulLaunch(42));

        executor.execute(stepRun, step);

        org.mockito.ArgumentCaptor<AssignmentCreateDto> captor = org.mockito.ArgumentCaptor.forClass(AssignmentCreateDto.class);
        verify(assignmentsPort).create(captor.capture());
        assertThat(captor.getValue().name()).matches("[A-Za-z0-9_]+");
    }

    private RpaProjectLaunchDto successfulLaunch(int assignmentId) {
        LocalDateTime now = LocalDateTime.now();
        return new RpaProjectLaunchDto(1, 3, 9, "robot-1", assignmentId, now, now, true, null, now);
    }

    private RpaProjectLaunchDto failedLaunch(int assignmentId, String robotName) {
        LocalDateTime now = LocalDateTime.now();
        return new RpaProjectLaunchDto(1, 3, 9, robotName, assignmentId, now, now, false, null, now);
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
