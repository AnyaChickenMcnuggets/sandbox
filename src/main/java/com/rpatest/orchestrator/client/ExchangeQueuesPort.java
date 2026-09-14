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

    /**
     * Тот же список, но с фильтрацией по natural key на стороне оркестратора (параметры
     * {@code NaturalKey}/{@code NaturalKeyPart} эндпоинта {@code GET .../v2/{id}/Items}) — не
     * тянуть тысячи элементов очереди целиком ради поиска нескольких известных ключей.
     *
     * @param naturalKeyPart {@code false} — точное совпадение, {@code true} — по части ключа
     *                       (используется для наших prefix-совпадений; финальная точная проверка
     *                       всё равно остаётся на вызывающей стороне, см. {@code
     *                       QueueCheckStepExecutor})
     */
    ListResultDto<ExchangeQueueValueDto> listItems(
            UUID queueId, int pageNumber, int pageSize, String naturalKey, boolean naturalKeyPart);

    void delete(UUID queueId);
}
