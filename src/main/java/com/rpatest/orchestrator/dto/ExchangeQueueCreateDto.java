package com.rpatest.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the subset of LTools.Dto.Orchestrator.ExchangeQueues.ExchangeQueueCreateDto used by
 * this service. Nullable-поля не заполняются по умолчанию и не попадают в JSON — минимизируем
 * запрос, расширяем набор полей только по факту необходимости.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExchangeQueueCreateDto(
        String name,
        String description,
        @JsonProperty("public") boolean isPublic,
        Integer ttl,
        Integer maxRetray,
        boolean robotCanDeleteOnlyItsOwnItem,
        boolean physicalRemoval) {

    public static ExchangeQueueCreateDto of(String name, String description) {
        return new ExchangeQueueCreateDto(name, description, true, null, null, false, true);
    }
}
