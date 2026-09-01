package com.schwab.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Schema(description = "Request body for creating a short URL.")
public record CreateUrlRequest(
        @Schema(description = "The URL to shorten. Must use the http or https scheme.",
                example = "https://example.com/some/very/long/path",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Schema(description = "Optional caller-chosen short code instead of a generated one. "
                + "3-32 characters: letters, digits, underscore, or hyphen. Returns 409 if already taken.",
                example = "summer-sale")
        @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$",
                message = "customAlias must be 3-32 characters using letters, numbers, _ or -")
        String customAlias,

        @Schema(description = "Optional expiry timestamp (ISO-8601, UTC). Must be in the future "
                + "at creation time. Once passed, redirecting returns 410 Gone.",
                example = "2026-12-31T23:59:59Z")
        Instant expiresAt
) {}
