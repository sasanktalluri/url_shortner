package com.schwab.urlshortener.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "short_urls", uniqueConstraints = {
        @UniqueConstraint(name = "uk_short_urls_short_code", columnNames = "short_code")
})
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 32)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ShortUrl() {}

    public ShortUrl(String shortCode, String originalUrl, Instant createdAt, Instant expiresAt) {
        this.shortCode = shortCode;
        this.originalUrl = originalUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public Long getId() { return id; }
    public String getShortCode() { return shortCode; }
    public String getOriginalUrl() { return originalUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
}
