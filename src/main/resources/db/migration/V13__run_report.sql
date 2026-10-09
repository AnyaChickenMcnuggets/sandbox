-- Structured outcome of a step (robot/assignment of a JOB, expected vs actual of a QUEUE_CHECK, ...).
-- Filled by the step executors; the run report is built from it instead of parsing detail text.
ALTER TABLE step_run ADD COLUMN result JSONB;

-- Frozen report of a finished run. A snapshot, not a live view: orchestrator data it describes
-- (queue contents) disappears on cleanup, and scenario edges are recreated on every scenario edit.
CREATE TABLE run_report (
    scenario_run_id BIGINT PRIMARY KEY REFERENCES scenario_run (id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    snapshot JSONB NOT NULL
);
