package com.rpatest.scenario.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScenarioStepEdgeIdTest {

    @Test
    void equalWhenBothIdsMatch() {
        ScenarioStepEdgeId a = new ScenarioStepEdgeId(1L, 2L);
        ScenarioStepEdgeId b = new ScenarioStepEdgeId(1L, 2L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void notEqualWhenIdsDiffer() {
        ScenarioStepEdgeId a = new ScenarioStepEdgeId(1L, 2L);
        ScenarioStepEdgeId b = new ScenarioStepEdgeId(1L, 3L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void notEqualToNullOrOtherType() {
        ScenarioStepEdgeId a = new ScenarioStepEdgeId(1L, 2L);

        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("not an edge id");
    }

    @Test
    void equalToItself() {
        ScenarioStepEdgeId a = new ScenarioStepEdgeId(1L, 2L);

        assertThat(a).isEqualTo(a);
    }
}
