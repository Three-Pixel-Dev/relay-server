-- Password reset tokens (hashed, single-use, expiry). Run manually in prod when ddl-auto is validate/none.
CREATE TABLE IF NOT EXISTS relay_password_reset_token (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES relay_user(id),
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_by BIGINT,
    is_enable BOOLEAN NOT NULL DEFAULT true
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_relay_password_reset_token_hash
    ON relay_password_reset_token(token_hash);

CREATE INDEX IF NOT EXISTS idx_relay_password_reset_token_user
    ON relay_password_reset_token(user_id);
