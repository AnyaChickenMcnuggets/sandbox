-- Прогоны (и их шаги) должны переживать удаление/редактирование сценария, а не исчезать вместе с
-- ним каскадно: scenario_run.scenario_id и step_run.step_id раньше были ON DELETE CASCADE — любое
-- удаление сценария стирало всю его историю прогонов, а PUT /scenarios/{id} (пересоздаёт шаги)
-- стирал step_run каждого предыдущего прогона этого сценария.

ALTER TABLE scenario_run ALTER COLUMN scenario_id DROP NOT NULL;
ALTER TABLE scenario_run DROP CONSTRAINT scenario_run_scenario_id_fkey;
ALTER TABLE scenario_run
    ADD CONSTRAINT scenario_run_scenario_id_fkey
    FOREIGN KEY (scenario_id) REFERENCES test_scenario (id) ON DELETE SET NULL;

ALTER TABLE step_run ALTER COLUMN step_id DROP NOT NULL;
ALTER TABLE step_run DROP CONSTRAINT step_run_step_id_fkey;
ALTER TABLE step_run
    ADD CONSTRAINT step_run_step_id_fkey
    FOREIGN KEY (step_id) REFERENCES scenario_step (id) ON DELETE SET NULL;

-- Денормализация имени/типа шага (по аналогии с scenario_run.scenario_name) — иначе после того,
-- как step_id обнулился, отобразить историю прогона было бы нечем.
ALTER TABLE step_run ADD COLUMN step_name VARCHAR(255);
ALTER TABLE step_run ADD COLUMN step_type VARCHAR(20);

UPDATE step_run sr
SET step_name = s.name, step_type = s.type
FROM scenario_step s
WHERE sr.step_id = s.id AND sr.step_name IS NULL;
