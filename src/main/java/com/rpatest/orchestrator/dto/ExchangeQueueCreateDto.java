package com.rpatest.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Mirrors the subset of LTools.Dto.Orchestrator.ExchangeQueues.ExchangeQueueCreateDto used by this service. */
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
