package com.rpatest.execution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "queue_item_result")
public class QueueItemResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "step_run_id", nullable = false)
    private Long stepRunId;

    @Column(name = "orchestrator_item_id", nullable = false)
    private UUID orchestratorItemId;

    @Column(name = "natural_key")
    private String naturalKey;

    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "value_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> valueSnapshot;

    @Column(name = "checked_at", nullable = false)
    private OffsetDateTime checkedAt;

    protected QueueItemResult() {
    }

    public QueueItemResult(
            Long stepRunId, UUID orchestratorItemId, String naturalKey, String status, Map<String, Object> valueSnapshot) {
        this.stepRunId = stepRunId;
        this.orchestratorItemId = orchestratorItemId;
        this.naturalKey = naturalKey;
        this.status = status;
        this.valueSnapshot = valueSnapshot;
        this.checkedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getStepRunId() {
        return stepRunId;
    }

    public UUID getOrchestratorItemId() {
        return orchestratorItemId;
    }

    public String getNaturalKey() {
        return naturalKey;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getValueSnapshot() {
        return valueSnapshot;
    }

    public OffsetDateTime getCheckedAt() {
        return checkedAt;
    }
}
