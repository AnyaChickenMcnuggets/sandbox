package com.rpatest.orchestrator.dto;

import java.util.Map;

/** Mirrors LTools.Dto.Orchestrator.ExchangeQueues.EnqueueExchangeQueueDto from orc_swagger.json. */
public record EnqueueExchangeQueueDto(
        Map<String, String> metadata, String naturalKey, Object value, Integer priority) {

    public static EnqueueExchangeQueueDto of(String naturalKey, Object value, Map<String, String> metadata) {
        return new EnqueueExchangeQueueDto(metadata, naturalKey, value, null);
    }
}
