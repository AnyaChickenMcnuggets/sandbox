package com.rpatest.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.config.JobStepConfig;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.RpaProjectVariablesPort;
import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.dto.AssignmentStatus;
import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class JobStepExecutor implements StepExecutor {

    private final AssignmentsPort assignmentsPort;
    private final RpaProjectVariablesPort rpaProjectVariablesPort;
    private final StatusPoller statusPoller;
    private final ObjectMapper objectMapper;

    public JobStepExecutor(
            AssignmentsPort assignmentsPort,
            RpaProjectVariablesPort rpaProjectVariablesPort,
            StatusPoller statusPoller,
            ObjectMapper objectMapper) {
        this.assignmentsPort = assignmentsPort;
        this.rpaProjectVariablesPort = rpaProjectVariablesPort;
        this.statusPoller = statusPoller;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScenarioStepType supports() {
        return ScenarioStepType.JOB;
    }

    @Override
    public void execute(StepRun stepRun, ScenarioStep step) {
        JobStepConfig config = objectMapper.convertValue(step.getConfig(), JobStepConfig.class);
        try {
            AssignmentDto created = assignmentsPort.create(
                    AssignmentCreateDto.manualRun(step.getName(), step.getName(), config.rpaProjectId()));
            stepRun.setOrchestratorAssignmentId(created.id());

            applyArguments(created.id(), config.argumentsOrEmpty());

            assignmentsPort.start(created.id());
            AssignmentDto finalState = statusPoller.pollUntilTerminal(created.id());
            if (finalState.status() == AssignmentStatus.ERROR) {
                throw new StepExecutionException(
                        "Задание завершилось с ошибкой: " + finalState.lastErrorMsg());
            }
        } catch (OrchestratorApiException e) {
            throw new StepExecutionException("Не удалось выполнить шаг задания '" + step.getName() + "'", e);
        }
    }

    private void applyArguments(int assignmentId, Map<String, String> arguments) {
        if (arguments.isEmpty()) {
            return;
        }
        List<RpaProjectVariableDto> variables = rpaProjectVariablesPort.get(assignmentId);
        List<RpaProjectVariableEditByIdDto> edits = variables.stream()
                .filter(v -> arguments.containsKey(v.name()))
                .map(v -> new RpaProjectVariableEditByIdDto(v.id(), arguments.get(v.name())))
                .toList();
        if (!edits.isEmpty()) {
            rpaProjectVariablesPort.update(assignmentId, edits);
        }
    }
}
