package com.rpatest.execution.web;

import java.time.OffsetDateTime;
import java.util.UUID;

public record QueueItemResponse(
        UUID id, String value, OffsetDateTime createdAt, String lastEventType, String lastEventText) {
}
