package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Draws 7 characters uniformly at random per candidate. SecureRandom, not java.util.Random: a
 * short code is a bearer credential (whoever holds it gets redirected to the target URL), so it
 * must not be predictable from prior outputs the way a plain LCG-based Random's sequence can be.
 * <p>
 * At 62^7 (~3.5e12) possible codes, a collision on any single insert is astronomically unlikely
 * at any realistic scale, but is retried below regardless, since it's cheap to.
 * <p>
 * Qualified "random" - the strategy pattern's other implementation alongside "sqids".
 */
@Component
@Qualifier("random")
class RandomShortCodeGenerator implements ShortCodeGenerator {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int CODE_LENGTH = 7;
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final SecureRandom secureRandom;
    private final ShortUrlWriter writer;

    @Autowired
    RandomShortCodeGenerator(ShortUrlWriter writer) {
        this(new SecureRandom(), writer);
    }

    RandomShortCodeGenerator(SecureRandom secureRandom, ShortUrlWriter writer) {
        this.secureRandom = secureRandom;
        this.writer = writer;
    }

    @Override
    public ShortUrl createShortUrl(String originalUrl, Instant now, Instant expiresAt) {
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return writer.save(generateCandidate(), originalUrl, now, expiresAt);
            } catch (DataIntegrityViolationException collision) {
                if (attempt == MAX_GENERATION_ATTEMPTS) {
                    throw collision;
                }
            }
        }
        throw new IllegalStateException("Unable to generate a unique short code");
    }

    private String generateCandidate() {
        StringBuilder result = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            result.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }
}
