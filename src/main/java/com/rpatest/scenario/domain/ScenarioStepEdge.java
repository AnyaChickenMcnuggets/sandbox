package com.rpatest.scenario.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "scenario_step_edge")
public class ScenarioStepEdge {

    @EmbeddedId
    private ScenarioStepEdgeId id;

    protected ScenarioStepEdge() {
    }

    public ScenarioStepEdge(Long fromStepId, Long toStepId) {
        this.id = new ScenarioStepEdgeId(fromStepId, toStepId);
    }

    public Long getFromStepId() {
        return id.getFromStepId();
    }

    public Long getToStepId() {
        return id.getToStepId();
    }
}
