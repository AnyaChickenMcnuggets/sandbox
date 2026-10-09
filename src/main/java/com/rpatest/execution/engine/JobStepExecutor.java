package com.rpatest.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.config.JobStepConfig;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.RpaProjectVariablesPort;
import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.orchestrator.util.OrchestratorNames;
import com.rpatest.orchestrator.util.OrchestratorNarration;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Создаёт и стартует Assignment, затем ждёт реального завершения на роботе (см. {@link
 * StatusPoller}) — статус самого Assignment ({@code Complete}) означает только, что оркестратор
 * принял его в очередь выполнения, и потому здесь не используется как признак успеха.
 */
@Component
public class JobStepExecutor implements StepExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobStepExecutor.class);

    private final AssignmentsPort assignmentsPort;
    private final RpaProjectVariablesPort rpaProjectVariablesPort;
    private final OrchestratorLookup orchestratorLookup;
    private final StatusPoller statusPoller;
    private final StepProgressReporter progressReporter;
    private final ObjectMapper objectMapper;

    public JobStepExecutor(
            AssignmentsPort assignmentsPort,
            RpaProjectVariablesPort rpaProjectVariablesPort,
            OrchestratorLookup orchestratorLookup,
            StatusPoller statusPoller,
            StepProgressReporter progressReporter,
            ObjectMapper objectMapper) {
        this.assignmentsPort = assignmentsPort;
        this.rpaProjectVariablesPort = rpaProjectVariablesPort;
        this.orchestratorLookup = orchestratorLookup;
        this.statusPoller = statusPoller;
        this.progressReporter = progressReporter;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScenarioStepType supports() {
        return ScenarioStepType.JOB;
    }

    @Override
    public void execute(StepRun stepRun, ScenarioStep step) {
        JobStepConfig config = objectMapper.convertValue(step.getConfig(), JobStepConfig.class);
        log.info("Step '{}' (id={}): starting JOB execution, config={}", step.getName(), step.getId(), config);
        try {
            validateProjectConfig(config, step);
            String projectLabel = orchestratorLookup.resolveProjectLabel(config.rpaProjectName(), config.rpaProjectId());
            int rpaProjectId = orchestratorLookup.resolveProjectId(config.rpaProjectName(), config.rpaProjectId());

            // Оркестратор принимает в имени только латиницу/цифры/подчёркивание. Имя также должно
            // быть уникальным для прогона: create() может упасть на поиск по имени (см. фолбэк в
            // AssignmentsClient), а при повторном запуске того же сценария имя шага не уникально.
            String assignmentName = OrchestratorNames.sanitize(
                    step.getName() + "_" + stepRun.getScenarioRunId() + "_" + step.getId());
            progressReporter.report(stepRun, "Creating assignment '" + assignmentName + "' for project '" + projectLabel + "'");
            AssignmentDto created = assignmentsPort.create(
                    AssignmentCreateDto.manualRun(assignmentName, step.getName(), rpaProjectId));
            stepRun.setOrchestratorAssignmentId(created.id());
            log.info("Step '{}': created Assignment id={} (name='{}')", step.getName(), created.id(), assignmentName);

            applyArguments(stepRun, assignmentName, created.id(), config.argumentsOrEmpty());

            progressReporter.report(stepRun, "Starting assignment '" + assignmentName + "'");
            assignmentsPort.start(created.id());
            log.info("Step '{}': Assignment id={} started (Start), tracking it now", step.getName(), created.id());

            Duration timeout = config.timeoutSeconds() != null ? Duration.ofSeconds(config.timeoutSeconds()) : null;
            Duration pollInterval =
                    config.pollIntervalSeconds() != null ? Duration.ofSeconds(config.pollIntervalSeconds()) : null;
            RpaProjectLaunchDto launch =
                    statusPoller.pollUntilTerminal(stepRun, created.id(), assignmentName, timeout, pollInterval);
            log.info("Step '{}': Assignment id={} finished, success={}, robot='{}'",
                    step.getName(), created.id(), launch.isSuccess(), launch.robotName());
            stepRun.setResult(describeResult(assignmentName, projectLabel, launch));
            if (!launch.isSuccess()) {
                throw new StepExecutionException("Assignment failed on robot '" + launch.robotName()
                        + "'" + OrchestratorNarration.describeQueueError(orchestratorLookup.findQueueEntries(created.id())));
            }
        } catch (OrchestratorApiException e) {
            log.error("Step '{}': orchestrator call failed", step.getName(), e);
            throw new StepExecutionException("Failed to execute job step '" + step.getName() + "'", e);
        }
    }

    private Map<String, Object> describeResult(String assignmentName, String projectLabel, RpaProjectLaunchDto launch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignmentName", assignmentName);
        result.put("projectName", projectLabel);
        result.put("robotName", launch.robotName());
        result.put("robotStartedAt", launch.robotStartedAt() == null ? null : launch.robotStartedAt().toString());
        result.put("completedAt", launch.completedAt() == null ? null : launch.completedAt().toString());
        result.put("success", launch.isSuccess());
        return result;
    }

    private void validateProjectConfig(JobStepConfig config, ScenarioStep step) {
        if (!config.hasProjectName() && config.rpaProjectId() == null) {
            throw new StepExecutionException(
                    "Step '" + step.getName() + "' has neither rpaProjectName nor rpaProjectId");
        }
    }

    private void applyArguments(StepRun stepRun, String assignmentName, int assignmentId, Map<String, String> arguments) {
        if (arguments.isEmpty()) {
            return;
        }
        progressReporter.report(stepRun, "Setting assignment arguments '" + assignmentName + "': " + arguments.keySet());
        List<RpaProjectVariableDto> variables = rpaProjectVariablesPort.get(assignmentId);
        List<RpaProjectVariableEditByIdDto> edits = variables.stream()
                .filter(v -> arguments.containsKey(v.name()))
                .map(v -> new RpaProjectVariableEditByIdDto(v.id(), arguments.get(v.name())))
                .toList();
        if (!edits.isEmpty()) {
            rpaProjectVariablesPort.update(assignmentId, edits);
            log.info("Assignment '{}' (id={}): applied {} argument(s)", assignmentName, assignmentId, edits.size());
        } else {
            log.warn("Assignment '{}' (id={}): none of the keys {} matched project variables",
                    assignmentName, assignmentId, arguments.keySet());
        }
    }
}
