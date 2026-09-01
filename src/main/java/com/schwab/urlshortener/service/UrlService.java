package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class UrlService {
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator generator;
    private final UrlValidator validator;
    private final Clock clock;
    private final String baseUrl;

    @Autowired
    public UrlService(
            ShortUrlRepository repository,
            ShortCodeGenerator generator,
            UrlValidator validator,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this(repository, generator, validator, Clock.systemUTC(), baseUrl);
    }

    UrlService(ShortUrlRepository repository,
               ShortCodeGenerator generator,
               UrlValidator validator,
               Clock clock,
               String baseUrl) {
        this.repository = repository;
        this.generator = generator;
        this.validator = validator;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    @Transactional
    public CreateUrlResponse create(CreateUrlRequest request) {
        validator.validate(request.url());

        Instant now = clock.instant();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }

        if (request.customAlias() != null && !request.customAlias().isBlank()) {
            return save(request, request.customAlias(), now, true);
        }

        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return save(request, generator.generate(), now, false);
            } catch (DataIntegrityViolationException collision) {
                if (attempt == MAX_GENERATION_ATTEMPTS) {
                    throw collision;
                }
            }
        }
        throw new IllegalStateException("Unable to generate a unique short code");
    }

    private CreateUrlResponse save(CreateUrlRequest request, String shortCode, Instant now, boolean customAlias) {
        try {
            ShortUrl saved = repository.saveAndFlush(
                    new ShortUrl(shortCode, request.url(), now, request.expiresAt()));

            return new CreateUrlResponse(
                    saved.getShortCode(),
                    baseUrl + "/" + saved.getShortCode(),
                    saved.getOriginalUrl(),
                    saved.getCreatedAt(),
                    saved.getExpiresAt());
        } catch (DataIntegrityViolationException e) {
            if (customAlias) {
                throw new AliasAlreadyExistsException(shortCode);
            }
            throw e;
        }
    }
}
