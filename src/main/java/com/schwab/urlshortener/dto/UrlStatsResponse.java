package com.schwab.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Click analytics and metadata for a short URL.")
public record UrlStatsResponse(
        @Schema(description = "The short code.", example = "UdBdLWZ")
        String shortCode,

        @Schema(description = "The original URL this short code redirects to.",
                example = "https://example.com/some/very/long/path")
        String originalUrl,

        @Schema(description = "When this short URL was created (UTC).",
                example = "2026-09-01T18:33:41.430052Z")
        Instant createdAt,

        @Schema(description = "When this short URL expires, or null if it never expires.",
                example = "2026-12-31T23:59:59Z", nullable = true)
        Instant expiresAt,

        @Schema(description = "false if the URL has been deactivated via DELETE.", example = "true")
        boolean active,

        @Schema(description = "Total number of times this short URL has been redirected.",
                example = "42")
        long clickCount,

        @Schema(description = "When this short URL was last redirected, or null if never.",
                example = "2026-09-01T19:10:22.512Z", nullable = true)
        Instant lastAccessedAt
) {}
