CREATE TABLE refresh_token (
    id          BIGSERIAL PRIMARY KEY,
    app_user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL UNIQUE,
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX idx_refresh_token_app_user_id ON refresh_token (app_user_id);
