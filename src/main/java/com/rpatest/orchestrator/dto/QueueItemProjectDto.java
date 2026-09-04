package com.rpatest.orchestrator.dto;

import java.time.LocalDateTime;

/**
 * Mirrors the subset of LTools.Dto.Orchestrator.ProjectQueue.QueueItemProjectDto used by this
 * service — запись проекта в очереди ожидания оркестратора ({@code RpaProjectQueue}). Пока запись
 * тут есть, робот проект ещё не подхватил; при ошибке выполнения сюда же пишется {@code errorMsg}.
 */
public record QueueItemProjectDto(
        int id,
        Integer assignmentId,
        String errorMsg,
        String errorRobotName,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
