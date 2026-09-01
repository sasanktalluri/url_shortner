package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;

import java.time.Instant;

/**
 * Produces and persists a ShortUrl with a fresh, unique code. Each implementation owns its own
 * collision-retry behavior, since whether retrying is meaningful - and why a collision could even
 * happen - depends entirely on the strategy: for RandomShortCodeGenerator a clash with another
 * generated code is possible, if rare; for SqidsShortCodeGenerator a generated code can never
 * clash with another generated code (the backing sequence never repeats a value), but it can
 * still clash with a pre-existing custom alias, since aliases and generated codes share one
 * uniqueness namespace.
 */
public interface ShortCodeGenerator {
    ShortUrl createShortUrl(String originalUrl, Instant now, Instant expiresAt);
}
