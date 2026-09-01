CREATE TABLE short_urls (
    id BIGSERIAL PRIMARY KEY,
    short_code VARCHAR(32) NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_short_urls_short_code UNIQUE (short_code)
);
