package com.rpatest.orchestrator.dto;

import java.time.LocalDateTime;

/**
 * Mirrors the subset of LTools.Dto.Orchestrator.RpaProjects.RpaProjectLaunchDto used by this
 * service. Единственный источник правды о том, что задание реально выполнилось на роботе —
 * {@code AssignmentStatus.Complete} лишь означает, что оркестратор поставил проект в очередь
 * выполнения (см. {@code GET /api/Assignments/v2/{id}}), а не то, что робот доделал работу.
 */
public record RpaProjectLaunchDto(
        int id,
        int projectId,
        Integer robotId,
        String robotName,
        Integer assignmentId,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Boolean success,
        LocalDateTime killedAt,
        LocalDateTime robotStartedAt) {

    public boolean isTerminal() {
        return completedAt != null || killedAt != null;
    }

    public boolean isSuccess() {
        return !Boolean.FALSE.equals(success);
    }
}
