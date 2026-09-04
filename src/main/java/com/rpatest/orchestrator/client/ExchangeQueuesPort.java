package com.rpatest.orchestrator.client;

import com.rpatest.orchestrator.dto.EnqueueExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueCreateDto;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import com.rpatest.orchestrator.dto.ExchangeQueueValueDto;
import com.rpatest.orchestrator.dto.ListResultDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeQueuesPort {

    void create(ExchangeQueueCreateDto request);

    List<ExchangeQueueDto> list();

    Optional<ExchangeQueueDto> findByName(String name);

    void enqueue(String queueName, EnqueueExchangeQueueDto item);

    ListResultDto<ExchangeQueueValueDto> listItems(UUID queueId, int pageNumber, int pageSize);

    void delete(UUID queueId);
}
