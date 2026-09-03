package com.rpatest.scenario.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rpatest.common.exception.NotFoundException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.domain.TestScenario;
import com.rpatest.scenario.repository.ScenarioStepEdgeRepository;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import com.rpatest.scenario.repository.TestScenarioRepository;
import com.rpatest.scenario.web.ScenarioRequest;
import com.rpatest.scenario.web.ScenarioResponse;
import com.rpatest.scenario.web.StepRequest;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScenarioServiceTest {

    private TestScenarioRepository scenarioRepository;
    private ScenarioStepRepository stepRepository;
    private ScenarioStepEdgeRepository edgeRepository;
    private ScenarioService service;

    private final AtomicLong stepIdSequence = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        scenarioRepository = mock(TestScenarioRepository.class);
        stepRepository = mock(ScenarioStepRepository.class);
        edgeRepository = mock(ScenarioStepEdgeRepository.class);
        service = new ScenarioService(scenarioRepository, stepRepository, edgeRepository, new DagValidator());

        when(scenarioRepository.save(any())).thenAnswer(invocation -> {
            TestScenario scenario = invocation.getArgument(0);
            setId(scenario, 1L);
            return scenario;
        });
        when(stepRepository.save(any())).thenAnswer(invocation -> {
            ScenarioStep step = invocation.getArgument(0);
            setId(step, stepIdSequence.getAndIncrement());
            return step;
        });
        when(stepRepository.findByScenarioIdOrderByPosition(any())).thenReturn(List.of());
        when(edgeRepository.findByStepIds(any())).thenReturn(List.of());
    }

    @Test
    void createPersistsScenarioStepsAndEdges() {
        ScenarioRequest request = new ScenarioRequest("scenario", "desc", List.of(
                new StepRequest("job1", ScenarioStepType.JOB, "Job 1", Map.of("rpaProjectId", 1), List.of("queue1")),
                new StepRequest("queue1", ScenarioStepType.QUEUE, "Queue 1", Map.of("name", "q1"), List.of())));

        ScenarioResponse response = service.create(request);

        assertThat(response.name()).isEqualTo("scenario");
    }

    @Test
    void createRejectsCyclicSteps() {
        ScenarioRequest request = new ScenarioRequest("scenario", "desc", List.of(
                new StepRequest("a", ScenarioStepType.JOB, "A", Map.of("rpaProjectId", 1), List.of("b")),
                new StepRequest("b", ScenarioStepType.JOB, "B", Map.of("rpaProjectId", 1), List.of("a"))));

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void getThrowsNotFoundWhenScenarioMissing() {
        when(scenarioRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteThrowsNotFoundWhenScenarioMissing() {
        when(scenarioRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(NotFoundException.class);
    }

    private void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
