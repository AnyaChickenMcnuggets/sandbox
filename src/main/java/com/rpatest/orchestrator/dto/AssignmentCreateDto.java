package com.rpatest.orchestrator.dto;

/** Mirrors LTools.Dto.Orchestrator.Assignments.AssignmentCreateDto from orc_swagger.json. */
public record AssignmentCreateDto(
        String name,
        String description,
        int rpaProjectId,
        Integer countRobots,
        boolean automaticApplyActiveRpaProject,
        boolean allowOverlay,
        Boolean withTriggers) {

    public static AssignmentCreateDto manualRun(String name, String description, int rpaProjectId) {
        return new AssignmentCreateDto(name, description, rpaProjectId, 1, false, false, false);
    }
}
