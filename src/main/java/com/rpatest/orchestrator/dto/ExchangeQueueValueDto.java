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
        String naturalKey,
        LocalDateTime createdAt,
        LocalDateTime readedRobotAt,
        LocalDateTime deletedAt,
        ExchangeQueueValueEventType lastEventType,
        String lastEventText) {

    public QueueItemDerivedStatus derivedStatus() {
        if (lastEventType != null) {
            return switch (lastEventType) {
                case SUCCESS -> QueueItemDerivedStatus.SUCCESS;
                case ERROR -> QueueItemDerivedStatus.ERROR;
                case BUSINESS_ERROR -> QueueItemDerivedStatus.BUSINESS_ERROR;
            };
        }
        return readedRobotAt != null ? QueueItemDerivedStatus.IN_PROGRESS : QueueItemDerivedStatus.NEW;
    }
}
