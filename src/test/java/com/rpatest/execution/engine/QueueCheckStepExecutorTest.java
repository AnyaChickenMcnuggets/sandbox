package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.config.OrchestratorProperties;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueEventType;
import com.rpatest.orchestrator.dto.ListResultDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueCheckStepExecutorTest {

    private ExchangeQueuesPort exchangeQueuesPort;
    private QueueCheckStepExecutor executor;

    @BeforeEach
    void setUp() {
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        OrchestratorProperties properties = new OrchestratorProperties();
        properties.getQueueCheckPolling().setInterval(Duration.ofMillis(10));
        properties.getQueueCheckPolling().setTimeout(Duration.ofMillis(150));
        StepProgressReporter progressReporter = new StepProgressReporter(mock(StepRunRepository.class));
        executor = new QueueCheckStepExecutor(exchangeQueuesPort, new ExchangeQueueProvisioner(exchangeQueuesPort),
                progressReporter, properties, new ObjectMapper());
    }

    @Test
    void supportsQueueCheckType() {
        assertThat(executor.supports()).isEqualTo(ScenarioStepType.QUEUE_CHECK);
    }

    @Test
    void succeedsWhenExpectedStatusCountsMatch() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(2, List.of(
                item("k1", ExchangeQueueValueEventType.SUCCESS),
                item("k2", ExchangeQueueValueEventType.ERROR))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "expectedStatusCounts", Map.of("SUCCESS", 1, "ERROR", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        assertThat(stepRun.getOrchestratorQueueId()).isEqualTo(queueId);
    }

    @Test
    void filtersByNaturalKeysWhenProvided() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(2, List.of(
                item("tracked", ExchangeQueueValueEventType.SUCCESS),
                item("ignored", ExchangeQueueValueEventType.ERROR))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("tracked"),
                "expectedStatusCounts", Map.of("SUCCESS", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void matchesNaturalKeyByPrefixWhenEnabled() {
        // один вход (naturalKey "tx-1") может породить несколько выходных транзакций с тем же
        // базовым ключом и дописанным суффиксом для трассировки: "tx-1-a", "tx-1-b"
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(3, List.of(
                item("tx-1-a", ExchangeQueueValueEventType.SUCCESS),
                item("tx-1-b", ExchangeQueueValueEventType.SUCCESS),
                item("tx-2-a", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("tx-1"),
                "naturalKeyPrefixMatch", true,
                "expectedStatusCounts", Map.of("SUCCESS", 2)));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void doesNotPrefixMatchWhenFlagIsAbsent() {
        // без naturalKeyPrefixMatch=true "tx-1" не должен матчить "tx-1-a" — точное совпадение
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(
                item("tx-1-a", ExchangeQueueValueEventType.SUCCESS))));

        ScenarioStep step = step(Map.of(
                "queueName", "q",
                "naturalKeys", List.of("tx-1"),
                "expectedStatusCounts", Map.of("SUCCESS", 1)));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    @Test
    void succeedsWhenMinTotalCountSatisfied() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(3, List.of(
                item("k1", null), item("k2", null), item("k3", null))));

        ScenarioStep step = step(Map.of("queueName", "q", "minTotalCount", 3));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);
    }

    @Test
    void throwsOnTimeoutWhenExpectationsNeverMet() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(1, List.of(item("k1", null))));

        ScenarioStep step = step(Map.of("queueName", "q", "expectedStatusCounts", Map.of("SUCCESS", 5)));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step))
                .isInstanceOf(StepExecutionException.class)
                .hasMessageContaining("SUCCESS=5");
    }

    @Test
    void createsMissingQueueInsteadOfFailingImmediately() {
        // get-or-create: DAG может быть собран так, что QUEUE_CHECK выполняется раньше
        // соответствующего QUEUE-шага — тогда проверяем пустую, только что созданную очередь,
        // а не падаем сразу с "не найдена".
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("missing"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExchangeQueueDto(queueId, "missing", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(0, List.of()));

        ScenarioStep step = step(Map.of("queueName", "missing", "minTotalCount", 0));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort).create(any());
        assertThat(stepRun.getOrchestratorQueueId()).isEqualTo(queueId);
    }

    @Test
    void throwsWhenQueueStillNotFoundAfterCreateAttempt() {
        when(exchangeQueuesPort.findByName("missing")).thenReturn(Optional.empty());

        ScenarioStep step = step(Map.of("queueName", "missing"));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    @Test
    void doesNotRecreateQueueThatAlreadyExists() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(queueId, "q", null, 0, 0)));
        when(exchangeQueuesPort.listItems(queueId, 0, 200)).thenReturn(ListResultDto.<ExchangeQueueValueDto>of(0, List.of()));

        ScenarioStep step = step(Map.of("queueName", "q", "minTotalCount", 0));
        StepRun stepRun = new StepRun(1L, 2L);

        executor.execute(stepRun, step);

        verify(exchangeQueuesPort, never()).create(any());
    }

    @Test
    void wrapsOrchestratorApiException() {
        when(exchangeQueuesPort.findByName("q")).thenThrow(new OrchestratorApiException("boom"));

        ScenarioStep step = step(Map.of("queueName", "q"));
        StepRun stepRun = new StepRun(1L, 2L);

        assertThatThrownBy(() -> executor.execute(stepRun, step)).isInstanceOf(StepExecutionException.class);
    }

    private ExchangeQueueValueDto item(String naturalKey, ExchangeQueueValueEventType eventType) {
        return new ExchangeQueueValueDto(UUID.randomUUID(), "v", naturalKey, null, null, null, eventType, null);
    }

    private ScenarioStep step(Map<String, Object> config) {
        return new ScenarioStep(100L, ScenarioStepType.QUEUE_CHECK, "check", config, 0);
    }
}
