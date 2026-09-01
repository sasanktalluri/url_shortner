package com.schwab.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "The created short URL and its metadata.")
public record CreateUrlResponse(
        @Schema(description = "The short code assigned to this URL.", example = "UdBdLWZ")
        String shortCode,

        @Schema(description = "The full short URL, ready to share.",
                example = "http://localhost:8080/UdBdLWZ")
        String shortUrl,

        @Schema(description = "The original URL that was shortened.",
                example = "https://example.com/some/very/long/path")
        String originalUrl,

        @Schema(description = "When this short URL was created (UTC).",
                example = "2026-09-01T18:33:41.430052Z")
        Instant createdAt,

        @Schema(description = "When this short URL expires, or null if it never expires.",
                example = "2026-12-31T23:59:59Z", nullable = true)
        Instant expiresAt
) {}
