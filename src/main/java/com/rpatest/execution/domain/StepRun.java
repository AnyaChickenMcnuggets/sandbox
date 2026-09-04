package com.rpatest.execution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "step_run")
public class StepRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_run_id", nullable = false)
    private Long scenarioRunId;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "orchestrator_assignment_id")
    private Integer orchestratorAssignmentId;

    @Column(name = "orchestrator_queue_id")
    private UUID orchestratorQueueId;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error_message")
    private String errorMessage;

    /** Человекочитаемое "что сейчас происходит" — единственный способ узнать прогресс живого шага
     * (очередь/задание) снаружи, пока он ещё не в терминальном статусе. Обновляется executor'ами
     * по ходу выполнения (не только в начале/конце), см. {@code StepProgressReporter}. */
    @Column(name = "detail")
    private String detail;

    @Column(name = "detail_updated_at")
    private OffsetDateTime detailUpdatedAt;

    protected StepRun() {
    }

    public StepRun(Long scenarioRunId, Long stepId) {
        this.scenarioRunId = scenarioRunId;
        this.stepId = stepId;
        this.status = RunStatus.PENDING;
    }

    public void markRunning() {
        this.status = RunStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
    }

    public void markSucceeded() {
        this.status = RunStatus.SUCCEEDED;
        this.finishedAt = OffsetDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = RunStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = OffsetDateTime.now();
    }

    public void updateDetail(String detail) {
        this.detail = detail;
        this.detailUpdatedAt = OffsetDateTime.now();
    }

    public void setOrchestratorAssignmentId(Integer orchestratorAssignmentId) {
        this.orchestratorAssignmentId = orchestratorAssignmentId;
    }

    public void setOrchestratorQueueId(UUID orchestratorQueueId) {
        this.orchestratorQueueId = orchestratorQueueId;
    }

    public Long getId() {
        return id;
    }

    public Long getScenarioRunId() {
        return scenarioRunId;
    }

    public Long getStepId() {
        return stepId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public Integer getOrchestratorAssignmentId() {
        return orchestratorAssignmentId;
    }

    public UUID getOrchestratorQueueId() {
        return orchestratorQueueId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getDetail() {
        return detail;
    }

    public OffsetDateTime getDetailUpdatedAt() {
        return detailUpdatedAt;
    }
}
