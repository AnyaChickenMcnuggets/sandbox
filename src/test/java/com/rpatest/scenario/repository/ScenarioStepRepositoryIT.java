package com.rpatest.scenario.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepEdge;
import com.rpatest.scenario.domain.ScenarioStepType;
import com.rpatest.scenario.domain.TestScenario;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ScenarioStepRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TestScenarioRepository scenarioRepository;

    @Autowired
    private ScenarioStepRepository stepRepository;

    @Autowired
    private ScenarioStepEdgeRepository edgeRepository;

    @Test
    void persistsScenarioWithStepsAndJsonbConfigAndEdges() {
        TestScenario scenario = scenarioRepository.save(new TestScenario("scenario", "desc"));

        ScenarioStep job = stepRepository.save(new ScenarioStep(
                scenario.getId(), ScenarioStepType.JOB, "job", Map.of("rpaProjectId", 7, "arguments", Map.of("x", "1")), 0));
        ScenarioStep queue = stepRepository.save(new ScenarioStep(
                scenario.getId(), ScenarioStepType.QUEUE, "queue", Map.of("name", "q1"), 1));
        edgeRepository.save(new ScenarioStepEdge(job.getId(), queue.getId()));

        List<ScenarioStep> steps = stepRepository.findByScenarioIdOrderByPosition(scenario.getId());
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getConfig()).containsEntry("rpaProjectId", 7);

        List<ScenarioStepEdge> edges = edgeRepository.findByStepIds(List.of(job.getId(), queue.getId()));
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).getFromStepId()).isEqualTo(job.getId());
        assertThat(edges.get(0).getToStepId()).isEqualTo(queue.getId());
    }

    @Test
    void deletingScenarioStepsCascadesToEdges() {
        TestScenario scenario = scenarioRepository.save(new TestScenario("scenario2", null));
        ScenarioStep a = stepRepository.save(new ScenarioStep(scenario.getId(), ScenarioStepType.JOB, "a", Map.of("rpaProjectId", 1), 0));
        ScenarioStep b = stepRepository.save(new ScenarioStep(scenario.getId(), ScenarioStepType.JOB, "b", Map.of("rpaProjectId", 1), 1));
        edgeRepository.save(new ScenarioStepEdge(a.getId(), b.getId()));

        stepRepository.deleteByScenarioId(scenario.getId());

        assertThat(stepRepository.findByScenarioIdOrderByPosition(scenario.getId())).isEmpty();
        assertThat(edgeRepository.findByStepIds(List.of(a.getId(), b.getId()))).isEmpty();
    }
}
