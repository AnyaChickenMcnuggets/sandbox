package com.rpatest.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.common.exception.NotFoundException;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.repository.QueueItemResultRepository;
import com.rpatest.execution.repository.StepRunRepository;
import com.rpatest.execution.web.QueueItemResponse;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueEventType;
import com.rpatest.orchestrator.dto.PageDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueAuditServiceTest {

    private StepRunRepository stepRunRepository;
    private QueueItemResultRepository queueItemResultRepository;
    private ExchangeQueuesPort exchangeQueuesPort;
    private QueueAuditService service;

    @BeforeEach
    void setUp() {
        stepRunRepository = mock(StepRunRepository.class);
        queueItemResultRepository = mock(QueueItemResultRepository.class);
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        service = new QueueAuditService(stepRunRepository, queueItemResultRepository, exchangeQueuesPort);
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
    void returnsItemsAndPersistsSnapshot() {
        UUID queueId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        ExchangeQueueValueDto item = new ExchangeQueueValueDto(
                itemId, "value", "key-1", null, null, null, ExchangeQueueValueEventType.SUCCESS, "ok");
        when(exchangeQueuesPort.listItems(queueId, 0, 100)).thenReturn(new PageDto<>(1, List.of(item)));

        List<QueueItemResponse> result = service.auditQueueItems(1L, 2L, 0, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(itemId);
        assertThat(result.get(0).lastEventType()).isEqualTo("SUCCESS");
        verify(queueItemResultRepository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsEmptyListWhenOrchestratorItemsAreNull() {
        UUID queueId = UUID.randomUUID();
        StepRun stepRun = new StepRun(1L, 2L);
        stepRun.setOrchestratorQueueId(queueId);
        when(stepRunRepository.findByScenarioRunIdAndStepId(1L, 2L)).thenReturn(Optional.of(stepRun));
        when(exchangeQueuesPort.listItems(queueId, 0, 100)).thenReturn(new PageDto<>(0, null));

        List<QueueItemResponse> result = service.auditQueueItems(1L, 2L, 0, 100);

        assertThat(result).isEmpty();
    }
}
