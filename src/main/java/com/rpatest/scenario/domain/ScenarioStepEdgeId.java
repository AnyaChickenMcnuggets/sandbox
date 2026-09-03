package com.rpatest.scenario.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ScenarioStepEdgeId implements Serializable {

    @Column(name = "from_step_id")
    private Long fromStepId;

    @Column(name = "to_step_id")
    private Long toStepId;

    protected ScenarioStepEdgeId() {
    }

    public ScenarioStepEdgeId(Long fromStepId, Long toStepId) {
        this.fromStepId = fromStepId;
        this.toStepId = toStepId;
    }

    public Long getFromStepId() {
        return fromStepId;
    }

    public Long getToStepId() {
        return toStepId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScenarioStepEdgeId that)) {
            return false;
        }
        return Objects.equals(fromStepId, that.fromStepId) && Objects.equals(toStepId, that.toStepId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromStepId, toStepId);
    }
}
