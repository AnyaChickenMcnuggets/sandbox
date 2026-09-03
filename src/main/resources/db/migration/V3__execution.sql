CREATE TABLE scenario_run (
    id           BIGSERIAL PRIMARY KEY,
    scenario_id  BIGINT NOT NULL REFERENCES test_scenario (id) ON DELETE CASCADE,
    status       VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'STOPPED')),
    started_at   TIMESTAMPTZ,
    finished_at  TIMESTAMPTZ,
    triggered_by VARCHAR(255)
);

CREATE INDEX idx_scenario_run_scenario_id ON scenario_run (scenario_id);

CREATE TABLE step_run (
    id                          BIGSERIAL PRIMARY KEY,
    scenario_run_id             BIGINT NOT NULL REFERENCES scenario_run (id) ON DELETE CASCADE,
    step_id                     BIGINT NOT NULL REFERENCES scenario_step (id) ON DELETE CASCADE,
    status                      VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'STOPPED')),
    orchestrator_assignment_id  INTEGER,
    orchestrator_queue_id       UUID,
    started_at                  TIMESTAMPTZ,
    finished_at                 TIMESTAMPTZ,
    error_message               TEXT
);

CREATE INDEX idx_step_run_scenario_run_id ON step_run (scenario_run_id);
CREATE INDEX idx_step_run_step_id ON step_run (step_id);

CREATE TABLE queue_item_result (
    id                  BIGSERIAL PRIMARY KEY,
    step_run_id         BIGINT NOT NULL REFERENCES step_run (id) ON DELETE CASCADE,
    orchestrator_item_id UUID NOT NULL,
    natural_key         VARCHAR(255),
    status              VARCHAR(50),
    value_snapshot      JSONB,
    checked_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_queue_item_result_step_run_id ON queue_item_result (step_run_id);
