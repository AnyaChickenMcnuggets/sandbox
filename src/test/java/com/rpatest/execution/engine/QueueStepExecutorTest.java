package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueStepExecutorTest {

    private ExchangeQueuesPort exchangeQueuesPort;
    private QueueStepExecutor executor;

    @BeforeEach
    void setUp() {
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        StepProgressReporter progressReporter = new StepProgressReporter(mock(StepRunRepository.class));
        executor = new QueueStepExecutor(
                exchangeQueuesPort, new ExchangeQueueProvisioner(exchangeQueuesPort), progressReporter, new ObjectMapper());
    }

    @Test
    void supportsQueueType() {
        assertThat(executor.supports()).isEqualTo(ScenarioStepType.QUEUE);
    }

    @Test
    void createsQueueWhenMissingAndEnqueuesTransactions() {
        UUID queueId = UUID.randomUUID();
        ScenarioStep step = step("My Queue", Map.of(
                "name", "my_queue",
                "transactions", List.of(Map.of("naturalKey", "k1", "value", "v1"))));
        StepRun stepRun = new StepRun(10L, 5L);
        when(exchangeQueuesPort.findByName("my_queue"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExchangeQueueDto(queueId, "my_queue", null, 0, 0)));

        executor.execute(stepRun, step);

        assertThat(stepRun.getOrchestratorQueueId()).isEqualTo(queueId);
        verify(exchangeQueuesPort).create(any());
        verify(exchangeQueuesPort, times(1)).enqueue(eq("my_queue"), any());
    }

    @Test
    void reusesExistingQueueWithoutRecreatingIt() {
        UUID queueId = UUID.randomUUID();
        ScenarioStep step = step("My Queue", Map.of("name", "my_queue"));
        StepRun stepRun = new StepRun(10L, 5L);
        when(exchangeQueuesPort.findByName("my_queue"))
                .thenReturn(Optional.of(new ExchangeQueueDto(queueId, "my_queue", null, 3, 0)));

        executor.execute(stepRun, step);

        assertThat(stepRun.getOrchestratorQueueId()).isEqualTo(queueId);
        verify(exchangeQueuesPort, never()).create(any());
    }

    @Test
    void sanitizesQueueNameFromConfig() {
        UUID queueId = UUID.randomUUID();
        ScenarioStep step = step("Queue", Map.of("name", "тест очередь"));
        StepRun stepRun = new StepRun(10L, 5L);
        when(exchangeQueuesPort.findByName("____________"))
                .thenReturn(Optional.of(new ExchangeQueueDto(queueId, "____________", null, 0, 0)));

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort).findByName("____________");
    }

    @Test
    void doesNotEnqueueWhenNoTransactionsConfigured() {
        UUID queueId = UUID.randomUUID();
        ScenarioStep step = step("Queue", Map.of("name", "q"));
        StepRun stepRun = new StepRun(10L, 5L);
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort, never()).enqueue(any(), any());
    }

    @Test
    void throwsWhenQueueStillNotFoundAfterCreate() {
        ScenarioStep step = step("Queue", Map.of("name", "q"));
        StepRun stepRun = new StepRun(10L, 5L);
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    @Test
    void wrapsOrchestratorApiExceptionIntoStepExecutionException() {
        ScenarioStep step = step("Queue", Map.of("name", "q"));
        StepRun stepRun = new StepRun(10L, 5L);
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new OrchestratorApiException("boom")).when(exchangeQueuesPort).create(any());

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("Queue");
    }

    private ScenarioStep step(String name, Map<String, Object> config) {
        return new ScenarioStep(100L, ScenarioStepType.QUEUE, name, config, 0);
    }
}
