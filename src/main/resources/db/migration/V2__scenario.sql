CREATE TABLE test_scenario (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE scenario_step (
    id          BIGSERIAL PRIMARY KEY,
    scenario_id BIGINT NOT NULL REFERENCES test_scenario (id) ON DELETE CASCADE,
    type        VARCHAR(20) NOT NULL CHECK (type IN ('JOB', 'QUEUE')),
    name        VARCHAR(255) NOT NULL,
    config      JSONB NOT NULL,
    position    INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_scenario_step_scenario_id ON scenario_step (scenario_id);

CREATE TABLE scenario_step_edge (
    from_step_id BIGINT NOT NULL REFERENCES scenario_step (id) ON DELETE CASCADE,
    to_step_id   BIGINT NOT NULL REFERENCES scenario_step (id) ON DELETE CASCADE,
    PRIMARY KEY (from_step_id, to_step_id)
);

CREATE INDEX idx_scenario_step_edge_to ON scenario_step_edge (to_step_id);
