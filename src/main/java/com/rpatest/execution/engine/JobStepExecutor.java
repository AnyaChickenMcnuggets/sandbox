package com.rpatest.execution.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpatest.execution.domain.StepRun;
import com.rpatest.execution.engine.config.JobStepConfig;
import com.rpatest.orchestrator.client.AssignmentsPort;
import com.rpatest.orchestrator.client.RpaProjectQueuePort;
import com.rpatest.orchestrator.client.RpaProjectVariablesPort;
import com.rpatest.orchestrator.client.RpaProjectsPort;
import com.rpatest.orchestrator.dto.AssignmentCreateDto;
import com.rpatest.orchestrator.dto.AssignmentDto;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectLaunchDto;
import com.rpatest.orchestrator.dto.RpaProjectShortDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableDto;
import com.rpatest.orchestrator.dto.RpaProjectVariableEditByIdDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.orchestrator.util.OrchestratorNames;
import com.rpatest.scenario.domain.ScenarioStep;
import com.rpatest.scenario.domain.ScenarioStepType;
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
    private final RpaProjectsPort rpaProjectsPort;
    private final RpaProjectVariablesPort rpaProjectVariablesPort;
    private final RpaProjectQueuePort rpaProjectQueuePort;
    private final StatusPoller statusPoller;
    private final StepProgressReporter progressReporter;
    private final ObjectMapper objectMapper;

    public JobStepExecutor(
            AssignmentsPort assignmentsPort,
            RpaProjectsPort rpaProjectsPort,
            RpaProjectVariablesPort rpaProjectVariablesPort,
            RpaProjectQueuePort rpaProjectQueuePort,
            StatusPoller statusPoller,
            StepProgressReporter progressReporter,
            ObjectMapper objectMapper) {
        this.assignmentsPort = assignmentsPort;
        this.rpaProjectsPort = rpaProjectsPort;
        this.rpaProjectVariablesPort = rpaProjectVariablesPort;
        this.rpaProjectQueuePort = rpaProjectQueuePort;
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
        log.info("Шаг '{}' (id={}): начинаю выполнение JOB, config={}", step.getName(), step.getId(), config);
        try {
            int rpaProjectId = resolveProjectId(stepRun, config, step);

            // Оркестратор принимает в имени только латиницу/цифры/подчёркивание. Имя также должно
            // быть уникальным для прогона: create() может упасть на поиск по имени (см. фолбэк в
            // AssignmentsClient), а при повторном запуске того же сценария имя шага не уникально.
            String assignmentName = OrchestratorNames.sanitize(
                    step.getName() + "_" + stepRun.getScenarioRunId() + "_" + step.getId());
            progressReporter.report(stepRun, "Создаю задание '" + assignmentName + "' по проекту id=" + rpaProjectId);
            AssignmentDto created = assignmentsPort.create(
                    AssignmentCreateDto.manualRun(assignmentName, step.getName(), rpaProjectId));
            stepRun.setOrchestratorAssignmentId(created.id());
            log.info("Шаг '{}': создан Assignment id={} (name='{}')", step.getName(), created.id(), assignmentName);

            applyArguments(stepRun, created.id(), config.argumentsOrEmpty());

            progressReporter.report(stepRun, "Запускаю задание id=" + created.id());
            assignmentsPort.start(created.id());
            log.info("Шаг '{}': Assignment id={} запущен (Start), начинаю отслеживание", step.getName(), created.id());

            RpaProjectLaunchDto launch = statusPoller.pollUntilTerminal(stepRun, created.id());
            log.info("Шаг '{}': Assignment id={} завершён, success={}, robot='{}'",
                    step.getName(), created.id(), launch.isSuccess(), launch.robotName());
            if (!launch.isSuccess()) {
                throw new StepExecutionException("Задание завершилось с ошибкой на роботе '" + launch.robotName()
                        + "'" + describeError(created.id()));
            }
        } catch (OrchestratorApiException e) {
            log.error("Шаг '{}': ошибка вызова оркестратора", step.getName(), e);
            throw new StepExecutionException("Не удалось выполнить шаг задания '" + step.getName() + "'", e);
        }
    }

    private int resolveProjectId(StepRun stepRun, JobStepConfig config, ScenarioStep step) {
        if (config.hasProjectName()) {
            progressReporter.report(stepRun, "Ищу проект по имени '" + config.rpaProjectName() + "'");
            RpaProjectShortDto project = rpaProjectsPort.findByName(config.rpaProjectName())
                    .orElseThrow(() -> new StepExecutionException(
                            "Проект '" + config.rpaProjectName() + "' не найден в оркестраторе (шаг '"
                                    + step.getName() + "')"));
            log.info("Шаг '{}': проект '{}' резолвлен в id={}", step.getName(), config.rpaProjectName(), project.id());
            return project.id();
        }
        if (config.rpaProjectId() != null) {
            return config.rpaProjectId();
        }
        throw new StepExecutionException(
                "В шаге '" + step.getName() + "' не указан ни rpaProjectName, ни rpaProjectId");
    }

    private String describeError(int assignmentId) {
        return rpaProjectQueuePort.findByAssignment(assignmentId).stream()
                .map(QueueItemProjectDto::errorMsg)
                .filter(msg -> msg != null && !msg.isBlank())
                .findFirst()
                .map(msg -> ": " + msg)
                .orElse("");
    }

    private void applyArguments(StepRun stepRun, int assignmentId, Map<String, String> arguments) {
        if (arguments.isEmpty()) {
            return;
        }
        progressReporter.report(stepRun, "Выставляю аргументы задания id=" + assignmentId + ": " + arguments.keySet());
        List<RpaProjectVariableDto> variables = rpaProjectVariablesPort.get(assignmentId);
        List<RpaProjectVariableEditByIdDto> edits = variables.stream()
                .filter(v -> arguments.containsKey(v.name()))
                .map(v -> new RpaProjectVariableEditByIdDto(v.id(), arguments.get(v.name())))
                .toList();
        if (!edits.isEmpty()) {
            rpaProjectVariablesPort.update(assignmentId, edits);
            log.info("Задание id={}: применено {} аргумент(ов)", assignmentId, edits.size());
        } else {
            log.warn("Задание id={}: ни один из ключей {} не совпал с переменными проекта", assignmentId, arguments.keySet());
        }
    }
}
