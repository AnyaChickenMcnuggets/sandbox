package com.rpatest.orchestrator.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Mirrors the subset of LTools.Dto.Orchestrator.ExchangeQueues.ExchangeQueueValueDto used by
 * this service. Даты у оркестратора приходят без смещения часового пояса, поэтому
 * {@link LocalDateTime}, а не {@code OffsetDateTime}.
 */
public record ExchangeQueueValueDto(
        UUID id,
        String value,
        LocalDateTime createdAt,
        LocalDateTime readedRobotAt,
        LocalDateTime deletedAt,
        ExchangeQueueValueEventType lastEventType,
        String lastEventText) {
}
