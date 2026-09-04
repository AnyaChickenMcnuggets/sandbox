package com.rpatest.scenario.domain;

public enum ScenarioStepType {
    JOB,
    QUEUE,
    /** Проверяет фактическое состояние транзакций в уже существующей очереди против ожиданий. */
    QUEUE_CHECK
}
