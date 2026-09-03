package com.rpatest.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Mirrors LTools.Dto.Orchestrator.Assignments.AssignmentCreateDto from orc_swagger.json.
 * Nullable-поля (countRobots, withTriggers) по умолчанию не заполняются и не попадают в JSON
 * ({@link JsonInclude.Include#NON_NULL}) — оркестратор чувствителен к лишним полям в запросе
 * создания задания. Расширять набор отправляемых полей только по факту реальной необходимости.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssignmentCreateDto(
        String name,
        String description,
        int rpaProjectId,
        Integer countRobots,
        boolean automaticApplyActiveRpaProject,
        boolean allowOverlay,
        Boolean withTriggers) {

    public static AssignmentCreateDto manualRun(String name, String description, int rpaProjectId) {
        return new AssignmentCreateDto(name, description, rpaProjectId, null, false, false, null);
    }
}
