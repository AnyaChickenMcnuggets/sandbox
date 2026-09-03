package com.rpatest.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Mirrors LTools.Dto.Orchestrator.ExchangeQueues.EnqueueExchangeQueueDto from orc_swagger.json.
 * Nullable-поля не заполняются по умолчанию и не попадают в JSON — минимизируем запрос.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnqueueExchangeQueueDto(
        Map<String, String> metadata, String naturalKey, Object value, Integer priority) {

    public static EnqueueExchangeQueueDto of(String naturalKey, Object value, Map<String, String> metadata) {
        return new EnqueueExchangeQueueDto(metadata, naturalKey, value, null);
    }
}
