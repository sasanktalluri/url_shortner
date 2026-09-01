package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Each write runs in its own transaction. On Postgres, a failed statement (e.g. a unique
 * constraint violation) aborts the whole transaction, not just that statement - if a caller
 * retried inserts inside one shared transaction, every retry after the first collision would
 * fail with "current transaction is aborted" instead of actually retrying.
 */
@Component
class ShortUrlWriter {
    private final ShortUrlRepository repository;

    ShortUrlWriter(ShortUrlRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ShortUrl save(String shortCode, String originalUrl, Instant createdAt, Instant expiresAt) {
        return repository.saveAndFlush(new ShortUrl(shortCode, originalUrl, createdAt, expiresAt));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void incrementClickCount(String shortCode, Instant accessedAt) {
        repository.incrementClickCount(shortCode, accessedAt);
    }
}
