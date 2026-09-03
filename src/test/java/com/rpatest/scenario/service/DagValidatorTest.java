package com.rpatest.scenario.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rpatest.common.exception.InvalidRequestException;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.web.StepRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DagValidatorTest {

    private final DagValidator validator = new DagValidator();

    @Test
    void acceptsLinearChain() {
        List<StepRequest> steps = List.of(
                step("a", List.of("b")),
                step("b", List.of("c")),
                step("c", List.of()));

        assertThatCode(() -> validator.validate(steps)).doesNotThrowAnyException();
    }

    @Test
    void acceptsFanOutBranching() {
        List<StepRequest> steps = List.of(
                step("job", List.of("queue1", "queue2")),
                step("queue1", List.of()),
                step("queue2", List.of()));

        assertThatCode(() -> validator.validate(steps)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateLocalId() {
        List<StepRequest> steps = List.of(step("a", List.of()), step("a", List.of()));

        assertThatThrownBy(() -> validator.validate(steps)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsReferenceToUnknownStep() {
        List<StepRequest> steps = List.of(step("a", List.of("missing")));

        assertThatThrownBy(() -> validator.validate(steps)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsCycle() {
        List<StepRequest> steps = List.of(
                step("a", List.of("b")),
                step("b", List.of("a")));

        assertThatThrownBy(() -> validator.validate(steps)).isInstanceOf(InvalidRequestException.class);
    }

    private StepRequest step(String localId, List<String> next) {
        return new StepRequest(localId, ScenarioStepType.JOB, localId, Map.of("rpaProjectId", 1), next);
    }
}
