package com.rpatest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.ScenarioRun;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.ScenarioRunRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CleanupServiceTest {

    private ScenarioRunRepository runRepository;
    private StepRunRepository stepRunRepository;
    private AssignmentsPort assignmentsPort;
    private ExchangeQueuesPort exchangeQueuesPort;
    private CleanupService service;

    @BeforeEach
    void setUp() {
        runRepository = mock(ScenarioRunRepository.class);
        stepRunRepository = mock(StepRunRepository.class);
        assignmentsPort = mock(AssignmentsPort.class);
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        service = new CleanupService(runRepository, stepRunRepository, assignmentsPort, exchangeQueuesPort);
    }

    @Test
    void throwsWhenScenarioHasNoRuns() {
        when(runRepository.findFirstByScenarioIdOrderByIdDesc(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cleanupLastRun(1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deletesAssignmentsAndQueuesFromLastRun() {
        ScenarioRun run = run(100L, 1L);
        when(runRepository.findFirstByScenarioIdOrderByIdDesc(1L)).thenReturn(Optional.of(run));
        UUID queueId = UUID.randomUUID();
        StepRun jobStep = new StepRun(100L, 1L);
        jobStep.setOrchestratorAssignmentId(42);
        StepRun queueStep = new StepRun(100L, 2L);
        queueStep.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of(jobStep, queueStep));

        List<String> failures = service.cleanupLastRun(1L);

        assertThat(failures).isEmpty();
        verify(assignmentsPort).delete(42);
        verify(exchangeQueuesPort).delete(queueId);
    }

    @Test
    void collectsFailuresInsteadOfAborting() {
        ScenarioRun run = run(100L, 1L);
        when(runRepository.findFirstByScenarioIdOrderByIdDesc(1L)).thenReturn(Optional.of(run));
        StepRun jobStep = new StepRun(100L, 1L);
        jobStep.setOrchestratorAssignmentId(42);
        when(stepRunRepository.findByScenarioRunId(100L)).thenReturn(List.of(jobStep));
        doThrow(new OrchestratorApiException("not found")).when(assignmentsPort).delete(42);

        List<String> failures = service.cleanupLastRun(1L);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).contains("assignment 42");
    }

    private ScenarioRun run(Long id, Long scenarioId) {
        ScenarioRun run = new ScenarioRun(scenarioId, "tester");
        setId(run, id);
        return run;
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
