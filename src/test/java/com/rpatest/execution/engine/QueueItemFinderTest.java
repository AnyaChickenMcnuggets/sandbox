package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueEventType;
import com.rpatest.orchestrator.dto.ListResultDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QueueItemFinderTest {

    private ExchangeQueuesPort exchangeQueuesPort;
    private QueueItemFinder finder;

    @BeforeEach
    void setUp() {
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        finder = new QueueItemFinder(exchangeQueuesPort);
    }

    @Test
    void emptyNaturalKeysScansWholeQueuePaginated() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.listItems(queueId, 0, 200))
                .thenReturn(ListResultDto.of(1, List.of(item("k1"))));

        List<ExchangeQueueValueDto> result = finder.find(queueId, Set.of(), false);

        assertThat(result).hasSize(1);
        verify(exchangeQueuesPort, never()).listItems(queueId, 0, 200, "k1", false);
    }

    @Test
    void nonEmptyNaturalKeysQueryOrchestratorFilterInsteadOfScanning() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "k1", false))
                .thenReturn(ListResultDto.of(1, List.of(item("k1"))));

        List<ExchangeQueueValueDto> result = finder.find(queueId, Set.of("k1"), false);

        assertThat(result).hasSize(1);
        verify(exchangeQueuesPort, never()).listItems(queueId, 0, 200);
    }

    @Test
    void reFiltersServerResultToExactKeysAsDefensiveMeasure() {
        // сервер (мок здесь заведомо "мягкий") вернул лишнее — свой точный фильтр всё равно отсекает
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.listItems(queueId, 0, 200, "k1", false))
                .thenReturn(ListResultDto.of(2, List.of(item("k1"), item("other"))));

        List<ExchangeQueueValueDto> result = finder.find(queueId, Set.of("k1"), false);

        assertThat(result).extracting(ExchangeQueueValueDto::naturalKey).containsExactly("k1");
    }

    @Test
    void excludesDeletedItems() {
        UUID queueId = UUID.randomUUID();
        when(exchangeQueuesPort.listItems(queueId, 0, 200))
                .thenReturn(ListResultDto.of(1, List.of(deletedItem("k1"))));

        List<ExchangeQueueValueDto> result = finder.find(queueId, Set.of(), false);

        assertThat(result).isEmpty();
    }

    private ExchangeQueueValueDto item(String naturalKey) {
        return new ExchangeQueueValueDto(
                UUID.randomUUID(), "v", naturalKey, null, null, null, ExchangeQueueValueEventType.SUCCESS, null, null);
    }

    private ExchangeQueueValueDto deletedItem(String naturalKey) {
        return new ExchangeQueueValueDto(UUID.randomUUID(), "v", naturalKey, null, null, LocalDateTime.now(),
                ExchangeQueueValueEventType.SUCCESS, null, null);
    }
}
