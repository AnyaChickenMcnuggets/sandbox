ALTER TABLE scenario_step DROP CONSTRAINT scenario_step_type_check;
ALTER TABLE scenario_step ADD CONSTRAINT scenario_step_type_check
    CHECK (type IN ('JOB', 'QUEUE', 'QUEUE_CHECK'));
