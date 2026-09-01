package com.schwab.urlshortener.dto;

import java.time.Instant;

public record UrlStatsResponse(
        String shortCode,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt,
        boolean active,
        long clickCount,
        Instant lastAccessedAt
) {}
