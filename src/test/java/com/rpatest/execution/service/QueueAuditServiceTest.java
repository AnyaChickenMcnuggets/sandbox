package com.rpatest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.QueueItemFinder;
import com.rpatest.execution.repository.QueueItemResultRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.QueueItemResponse;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueEventType;
import com.rpatest.orchestrator.dto.ListResultDto;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueAuditServiceTest {

    private StepRunRepository stepRunRepository;
    private QueueItemResultRepository queueItemResultRepository;
    private ExchangeQueuesPort exchangeQueuesPort;
    private ScenarioStepRepository scenarioStepRepository;
    private QueueAuditService service;

    @BeforeEach
    void setUp() {
        stepRunRepository = mock(StepRunRepository.class);
        queueItemResultRepository = mock(QueueItemResultRepository.class);
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        scenarioStepRepository = mock(ScenarioStepRepository.class);
        service = new QueueAuditService(stepRunRepository, queueItemResultRepository, exchangeQueuesPort,
                scenarioStepRepository, new QueueItemFinder(exchangeQueuesPort), new ObjectMapper());
    }

    @Test
    void throwsNotFoundWhenStepRunMissing() {
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.auditQueueItems(1L, 2L, 0, 100)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void throwsInvalidRequestWhenStepDidNotCreateQueue() {
        StepRun stepRun = new StepRun(1L, 2L);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));

        assertThatThrownBy(() -> service.auditQueueItems(1L, 2L, 0, 100)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void showsWholeQueuePageWhenStepHasNoKnownNaturalKeys() {
        // scenario_step не найден (например, уже удалён — см. денормализацию в AGENTS.md) —
        // сужать нечем, остаётся прежний постраничный просмотр всей очереди
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        when(scenarioStepRepository.findById(2L)).thenReturn(Optional.empty());
        ExchangeQueueValueDto item = new ExchangeQueueValueDto(
                itemId, "value", "key-1", null, null, null, ExchangeQueueValueEventType.SUCCESS, "ok", null);
        when(exchangeQueuesPort.listItems(queueId, 0, 100)).thenReturn(ListResultDto.of(1, List.of(item)));

        List<QueueItemResponse> result = service.auditQueueItems(1L, 2L, 0, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(itemId);
        assertThat(result.get(0).lastEventType()).isEqualTo("SUCCESS");
        verify(queueItemResultRepository).save(any());
    }

    @Test
    void autoFiltersToQueueStepsOwnTransactionNaturalKeysByDefault() {
        // QUEUE-шаг без явного naturalKey в запросе — показываем только то, что сам шаг создал,
        // не всю (потенциально многотысячную) очередь
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        ScenarioStep queueStep = new ScenarioStep(10L, ScenarioStepType.QUEUE, "queueIn",
                Map.of("name", "q", "transactions", List.of(Map.of("naturalKey", "tx-1"))), 0);
        when(scenarioStepRepository.findById(2L)).thenReturn(Optional.of(queueStep));
        ExchangeQueueValueDto item = new ExchangeQueueValueDto(
                itemId, "value", "tx-1", null, null, null, ExchangeQueueValueEventType.SUCCESS, "ok", null);
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "tx-1", false))
                .thenReturn(ListResultDto.of(1, List.of(item)));

        List<QueueItemResponse> result = service.auditQueueItems(1L, 2L, 0, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).naturalKey()).isEqualTo("tx-1");
        verify(exchangeQueuesPort, never()).listItems(queueId, 0, 100);
    }

    @Test
    void autoFiltersToQueueCheckStepsTrackedNaturalKeysByDefault() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        ScenarioStep checkStep = new ScenarioStep(10L, ScenarioStepType.QUEUE_CHECK, "checkOutput",
                Map.of("queueName", "q", "naturalKeys", List.of("tx-1"), "naturalKeyPrefixMatch", true), 0);
        when(scenarioStepRepository.findById(2L)).thenReturn(Optional.of(checkStep));
        ExchangeQueueValueDto item = new ExchangeQueueValueDto(
                itemId, "value", "tx-1-a", null, null, null, ExchangeQueueValueEventType.SUCCESS, "ok", null);
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "tx-1", true))
                .thenReturn(ListResultDto.of(1, List.of(item)));

        List<QueueItemResponse> result = service.auditQueueItems(1L, 2L, 0, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).naturalKey()).isEqualTo("tx-1-a");
    }

    @Test
    void showsWholeQueueForQueueCheckStepThatWatchesEntireQueue() {
        // QUEUE_CHECK без naturalKeys (проверяет только minTotalCount) осознанно следит за всей
        // очередью — сужать здесь нечем и не нужно, это не баг
        UUID queueId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        ScenarioStep checkStep = new ScenarioStep(10L, ScenarioStepType.QUEUE_CHECK, "checkAll",
                Map.of("queueName", "q", "minTotalCount", 3), 0);
        when(scenarioStepRepository.findById(2L)).thenReturn(Optional.of(checkStep));
        when(exchangeQueuesPort.listItems(queueId, 0, 100)).thenReturn(ListResultDto.of(0, List.of()));

        service.auditQueueItems(1L, 2L, 0, 100);

        verify(exchangeQueuesPort).listItems(queueId, 0, 100);
    }

    @Test
    void explicitNaturalKeyOverridesAutoDerivedFilter() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        ExchangeQueueValueDto item = new ExchangeQueueValueDto(
                itemId, "value", "tx-2", null, null, null, ExchangeQueueValueEventType.SUCCESS, "ok", null);
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "tx-2", false))
                .thenReturn(ListResultDto.of(1, List.of(item)));

        List<QueueItemResponse> result = service.auditQueueItems(1L, 2L, 0, 100, "tx-2", false);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).naturalKey()).isEqualTo("tx-2");
        // явный фильтр не требует похода за scenario_step вообще
        verify(scenarioStepRepository, never()).findById(any());
    }

    @Test
    void returnsEmptyListWhenOrchestratorItemsAreNull() {
        UUID queueId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        when(scenarioStepRepository.findById(2L)).thenReturn(Optional.empty());
        when(exchangeQueuesPort.listItems(queueId, 0, 100)).thenReturn(ListResultDto.of(0, null));

        List<QueueItemResponse> result = service.auditQueueItems(1L, 2L, 0, 100);

        assertThat(result).isEmpty();
    }
}
