package com.rpatest.scenario.service;

import com.rpatest.common.exception.NotFoundException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepEdge;
import com.rpatest.scenario.domain.TestScenario;
import com.rpatest.scenario.repository.ScenarioStepEdgeRepository;
import com.rpatest.scenario.repository.ScenarioStepRepository;
import com.rpatest.scenario.repository.TestScenarioRepository;
import com.rpatest.scenario.web.ScenarioRequest;
import com.rpatest.scenario.web.ScenarioResponse;
import com.rpatest.scenario.web.StepRequest;
import com.rpatest.scenario.web.StepResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ScenarioService {

    private final TestScenarioRepository scenarioRepository;
    private final ScenarioStepRepository stepRepository;
    private final ScenarioStepEdgeRepository edgeRepository;
    private final DagValidator dagValidator;

    public ScenarioService(
            TestScenarioRepository scenarioRepository,
            ScenarioStepRepository stepRepository,
            ScenarioStepEdgeRepository edgeRepository,
            DagValidator dagValidator) {
        this.scenarioRepository = scenarioRepository;
        this.stepRepository = stepRepository;
        this.edgeRepository = edgeRepository;
        this.dagValidator = dagValidator;
    }

    public ScenarioResponse create(ScenarioRequest request) {
        dagValidator.validate(request.steps());
        TestScenario scenario = scenarioRepository.save(new TestScenario(request.name(), request.description()));
        return persistStepsAndAssemble(scenario, request);
    }

    public ScenarioResponse update(Long scenarioId, ScenarioRequest request) {
        dagValidator.validate(request.steps());
        TestScenario scenario = findScenarioOrThrow(scenarioId);
        scenario.update(request.name(), request.description());
        stepRepository.deleteByScenarioId(scenarioId);
        return persistStepsAndAssemble(scenario, request);
    }

    @Transactional(readOnly = true)
    public ScenarioResponse get(Long scenarioId) {
        TestScenario scenario = findScenarioOrThrow(scenarioId);
        return assemble(scenario);
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> list() {
        return scenarioRepository.findAll().stream().map(this::assemble).toList();
    }

    public void delete(Long scenarioId) {
        if (!scenarioRepository.existsById(scenarioId)) {
            throw new NotFoundException("Сценарий не найден: " + scenarioId);
        }
        scenarioRepository.deleteById(scenarioId);
    }

    private ScenarioResponse persistStepsAndAssemble(TestScenario scenario, ScenarioRequest request) {
        Map<String, Long> localIdToStepId = new HashMap<>();
        List<ScenarioStep> savedSteps = new ArrayList<>();
        int position = 0;
        for (StepRequest stepRequest : request.steps()) {
            ScenarioStep saved = stepRepository.save(new ScenarioStep(
                    scenario.getId(), stepRequest.type(), stepRequest.name(), stepRequest.config(), position++));
            localIdToStepId.put(stepRequest.localId(), saved.getId());
            savedSteps.add(saved);
        }
        for (StepRequest stepRequest : request.steps()) {
            Long fromId = localIdToStepId.get(stepRequest.localId());
            for (String nextLocalId : stepRequest.nextLocalIdsOrEmpty()) {
                edgeRepository.save(new ScenarioStepEdge(fromId, localIdToStepId.get(nextLocalId)));
            }
        }
        return assemble(scenario);
    }

    private ScenarioResponse assemble(TestScenario scenario) {
        List<ScenarioStep> steps = stepRepository.findByScenarioIdOrderByPosition(scenario.getId());
        List<Long> stepIds = steps.stream().map(ScenarioStep::getId).toList();
        List<ScenarioStepEdge> edges = stepIds.isEmpty() ? List.of() : edgeRepository.findByStepIds(stepIds);

        Map<Long, List<Long>> outgoing = new HashMap<>();
        for (ScenarioStepEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getFromStepId(), k -> new ArrayList<>()).add(edge.getToStepId());
        }

        List<StepResponse> stepResponses = steps.stream()
                .map(step -> new StepResponse(
                        step.getId(),
                        step.getType(),
                        step.getName(),
                        step.getConfig(),
                        outgoing.getOrDefault(step.getId(), List.of())))
                .toList();

        return new ScenarioResponse(
                scenario.getId(),
                scenario.getName(),
                scenario.getDescription(),
                scenario.getCreatedAt(),
                scenario.getUpdatedAt(),
                stepResponses);
    }

    private TestScenario findScenarioOrThrow(Long scenarioId) {
        return scenarioRepository.findById(scenarioId)
                .orElseThrow(() -> new NotFoundException("Сценарий не найден: " + scenarioId));
    }
}
