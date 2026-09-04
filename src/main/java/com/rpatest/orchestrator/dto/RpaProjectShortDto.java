package com.rpatest.orchestrator.dto;

/** Mirrors the subset of LTools.Dto.Orchestrator.RpaProjects.RpaProjectShortDto used by this service. */
public record RpaProjectShortDto(int id, String name, String description, Integer parentId, boolean active) {
}
