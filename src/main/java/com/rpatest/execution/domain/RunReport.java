package com.rpatest.execution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Frozen report of a finished run (see {@code RunReportService}). The snapshot is stored as a
 * generic JSON map, the same proven mapping as {@code ScenarioStep.config}; the typed
 * {@code RunReportSnapshot} is converted from/to it by the service.
 */
@Entity
@Table(name = "run_report")
public class RunReport {

    @Id
    @Column(name = "scenario_run_id")
    private Long scenarioRunId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false)
    private Map<String, Object> snapshot;

    protected RunReport() {
    }

    public RunReport(Long scenarioRunId, Map<String, Object> snapshot) {
        this.scenarioRunId = scenarioRunId;
        this.snapshot = snapshot;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getScenarioRunId() {
        return scenarioRunId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public Map<String, Object> getSnapshot() {
        return snapshot;
    }
}
