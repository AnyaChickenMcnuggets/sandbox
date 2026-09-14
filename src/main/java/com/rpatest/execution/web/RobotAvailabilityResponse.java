package com.rpatest.execution.web;

/**
 * Снимок доступности роботов на оркестраторе прямо сейчас — то же самое условие, которое
 * {@code POST /api/v1/scenarios/{id}/run} проверяет перед запуском (см.
 * {@code ExecutionService.requireEnoughFreeRobots}). Предназначен для поллинга фронтом: показать
 * пользователю "можно ли сейчас запускать" и заблокировать кнопку запуска ещё до самой попытки, а
 * не только по факту получения {@code 409} на самом запуске.
 *
 * @param launchAllowed {@code freeRobots >= minFreeRobots} — то же самое условие, по которому
 *                      {@code POST .../run} либо пройдёт, либо ответит {@code 409}
 */
public record RobotAvailabilityResponse(int freeRobots, int totalRobots, int minFreeRobots, boolean launchAllowed) {
}
