ALTER TABLE scenario_run ADD COLUMN scenario_name VARCHAR(255);

UPDATE scenario_run r
SET scenario_name = s.name
FROM test_scenario s
WHERE r.scenario_id = s.id AND r.scenario_name IS NULL;
