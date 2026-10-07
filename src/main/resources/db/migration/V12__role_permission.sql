CREATE TABLE role_permission (
    id         BIGSERIAL PRIMARY KEY,
    role       VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'OPERATOR', 'VIEWER')),
    permission VARCHAR(50) NOT NULL,
    CONSTRAINT uq_role_permission UNIQUE (role, permission)
);

-- Стартовая матрица = прежняя жёстко зашитая в SecurityConfig. ADMIN строк не имеет: роль
-- всегда обладает всеми правами и не редактируется (ADR 0005).
INSERT INTO role_permission (role, permission) VALUES
    ('VIEWER',   'SCENARIO_READ'),
    ('VIEWER',   'RUN_READ'),
    ('VIEWER',   'ORCHESTRATOR_READ'),
    ('OPERATOR', 'SCENARIO_READ'),
    ('OPERATOR', 'RUN_READ'),
    ('OPERATOR', 'ORCHESTRATOR_READ'),
    ('OPERATOR', 'SCENARIO_WRITE'),
    ('OPERATOR', 'RUN_START'),
    ('OPERATOR', 'RUN_STOP'),
    ('OPERATOR', 'CLEANUP');
