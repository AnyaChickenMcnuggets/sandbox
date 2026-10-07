package com.rpatest.execution.engine;

import com.rpatest.orchestrator.client.RpaProjectQueuePort;
import com.rpatest.orchestrator.client.RpaProjectsPort;
import com.rpatest.orchestrator.dto.QueueItemProjectDto;
import com.rpatest.orchestrator.dto.RpaProjectShortDto;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Единственное место, где id сущностей оркестратора резолвятся в то, что дальше уйдёт человеку
 * (шаг сценария знает только {@code rpaProjectId}/{@code rpaProjectName}, а очередь ожидания
 * запуска — только по {@code assignmentId}). До выделения этого класса `JobStepExecutor` и
 * `StatusPoller` независимо вызывали {@code RpaProjectQueuePort.findByAssignment} для одного и
 * того же {@code assignmentId} за разными полями одного ответа — не просто дублирование текста, а
 * дублирование самого HTTP-вызова.
 */
@Component
public class OrchestratorLookup {

    private final RpaProjectsPort rpaProjectsPort;
    private final RpaProjectQueuePort rpaProjectQueuePort;

    public OrchestratorLookup(RpaProjectsPort rpaProjectsPort, RpaProjectQueuePort rpaProjectQueuePort) {
        this.rpaProjectsPort = rpaProjectsPort;
        this.rpaProjectQueuePort = rpaProjectQueuePort;
    }

    /**
     * Id проекта, которым реально вызывается API оркестратора: если указано {@code projectName},
     * ищет по нему (это и есть источник истины для id в этом случае); если только {@code
     * projectId} — возвращает его как есть.
     */
    public int resolveProjectId(String projectName, Integer projectId) {
        if (projectName != null && !projectName.isBlank()) {
            return rpaProjectsPort.findByName(projectName)
                    .map(RpaProjectShortDto::id)
                    .orElseThrow(() -> new StepExecutionException("Project '" + projectName + "' not found in the orchestrator"));
        }
        return projectId;
    }

    /**
     * Человекочитаемое имя проекта для статусов/логов — если задано {@code projectName}, используем
     * как есть; если только {@code projectId}, best-effort резолвим имя отдельным вызовом. Неудача
     * резолвинга не должна ронять шаг (имя нужно только для отображения) — показываем id.
     */
    public String resolveProjectLabel(String projectName, Integer projectId) {
        if (projectName != null && !projectName.isBlank()) {
            return projectName;
        }
        return rpaProjectsPort.findById(projectId)
                .map(RpaProjectShortDto::name)
                .orElse("id=" + projectId);
    }

    /** Записи задания {@code assignmentId} в очереди ожидания запуска проектов оркестратора. */
    public List<QueueItemProjectDto> findQueueEntries(int assignmentId) {
        return rpaProjectQueuePort.findByAssignment(assignmentId);
    }
}
