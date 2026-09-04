package com.rpatest.execution.web;

import java.time.LocalDateTime;
import java.util.UUID;

public record QueueItemResponse(
        UUID id, String naturalKey, String value, LocalDateTime createdAt, String lastEventType, String lastEventText) {
}
