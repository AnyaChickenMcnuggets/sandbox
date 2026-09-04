package com.rpatest.orchestrator.dto;

/**
 * У элемента очереди оркестратора нет отдельного поля "статус" — жизненный цикл выводится из
 * комбинации {@code readedRobotAt} (взят ли роботом в обработку) и {@code lastEventType}
 * (итоговое событие обработки): New → InProgress → (Success | Error | BusinessError).
 */
public enum QueueItemDerivedStatus {
    NEW,
    IN_PROGRESS,
    SUCCESS,
    ERROR,
    BUSINESS_ERROR
}
