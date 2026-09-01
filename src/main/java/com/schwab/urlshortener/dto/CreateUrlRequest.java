package com.schwab.urlshortener.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateUrlRequest(
        @jakarta.validation.constraints.NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,

        @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$",
                message = "customAlias must be 3-32 characters using letters, numbers, _ or -")
        String customAlias,

        Instant expiresAt
) {}
