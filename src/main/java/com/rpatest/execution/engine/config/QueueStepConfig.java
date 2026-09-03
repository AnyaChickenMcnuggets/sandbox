package com.rpatest.execution.engine.config;

import java.util.List;

/** Форма JSONB-конфига ScenarioStep(type=QUEUE), сохраняемая через scenario API. */
public record QueueStepConfig(
        String name, String description, Integer ttl, Integer maxRetray, List<TransactionTemplate> transactions) {

    public List<TransactionTemplate> transactionsOrEmpty() {
        return transactions == null ? List.of() : transactions;
    }
}
