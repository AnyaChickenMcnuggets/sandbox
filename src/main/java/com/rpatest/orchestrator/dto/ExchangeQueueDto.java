package com.rpatest.orchestrator.dto;

import java.util.UUID;

/** Mirrors the subset of LTools.Dto.Orchestrator.ExchangeQueues.ExchangeQueueDto used by this service. */
public record ExchangeQueueDto(UUID id, String name, String description, int countItems, int countReadedItems) {
}
