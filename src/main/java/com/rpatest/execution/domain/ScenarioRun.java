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

@Entity
@Table(name = "scenario_run")
public class ScenarioRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "triggered_by")
    private String triggeredBy;

    protected ScenarioRun() {
    }

    public ScenarioRun(Long scenarioId, String triggeredBy) {
        this.scenarioId = scenarioId;
        this.triggeredBy = triggeredBy;
        this.status = RunStatus.PENDING;
    }

    public void markRunning() {
        this.status = RunStatus.RUNNING;
        this.startedAt = OffsetDateTime.now();
    }

    public void finish(RunStatus terminalStatus) {
        this.status = terminalStatus;
        this.finishedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getScenarioId() {
        return scenarioId;
    }

    public RunStatus getStatus() {
        return status;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }
}
