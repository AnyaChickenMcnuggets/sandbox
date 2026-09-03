package com.rpatest.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Mirrors LTools.Enums.ExchangeQueueValueEventType (0-2) from orc_swagger.json. */
public enum ExchangeQueueValueEventType {
    SUCCESS(0),
    ERROR(1),
    BUSINESS_ERROR(2);

    private final int code;

    ExchangeQueueValueEventType(int code) {
        this.code = code;
    }

    @JsonValue
    public int getCode() {
        return code;
    }

    @JsonCreator
    public static ExchangeQueueValueEventType fromCode(int code) {
        for (ExchangeQueueValueEventType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Неизвестный ExchangeQueueValueEventType код: " + code);
    }
}
