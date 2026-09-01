ALTER TABLE short_urls
    ADD COLUMN click_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_accessed_at TIMESTAMPTZ;
