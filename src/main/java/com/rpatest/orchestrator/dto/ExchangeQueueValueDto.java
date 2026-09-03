package com.rpatest.orchestrator.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Mirrors the subset of LTools.Dto.Orchestrator.ExchangeQueues.ExchangeQueueValueDto used by this service. */
public record ExchangeQueueValueDto(
        UUID id,
        String value,
        OffsetDateTime createdAt,
        OffsetDateTime readedRobotAt,
        OffsetDateTime deletedAt,
        ExchangeQueueValueEventType lastEventType,
        String lastEventText) {
}
