package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class UrlService {
    private final ShortUrlRepository repository;
    private final ShortUrlWriter writer;
    private final ShortCodeGenerator generator;
    private final UrlValidator validator;
    private final Clock clock;
    private final String baseUrl;

    @Autowired
    public UrlService(
            ShortUrlRepository repository,
            ShortUrlWriter writer,
            @Qualifier("sqids") ShortCodeGenerator generator,
            UrlValidator validator,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this(repository, writer, generator, validator, Clock.systemUTC(), baseUrl);
    }

    UrlService(ShortUrlRepository repository,
               ShortUrlWriter writer,
               ShortCodeGenerator generator,
               UrlValidator validator,
               Clock clock,
               String baseUrl) {
        this.repository = repository;
        this.writer = writer;
        this.generator = generator;
        this.validator = validator;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    public CreateUrlResponse create(CreateUrlRequest request) {
        validator.validate(request.url());

        Instant now = clock.instant();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }

        ShortUrl saved = request.customAlias() != null && !request.customAlias().isBlank()
                ? saveWithCustomAlias(request, now)
                : generator.createShortUrl(request.url(), now, request.expiresAt());

        return toResponse(saved);
    }

    public UrlStatsResponse getStats(String shortCode) {
        ShortUrl shortUrl = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        return new UrlStatsResponse(
                shortUrl.getShortCode(),
                shortUrl.getOriginalUrl(),
                shortUrl.getCreatedAt(),
                shortUrl.getExpiresAt(),
                shortUrl.isActive(),
                shortUrl.getClickCount(),
                shortUrl.getLastAccessedAt());
    }

    private ShortUrl saveWithCustomAlias(CreateUrlRequest request, Instant now) {
        try {
            return writer.save(request.customAlias(), request.url(), now, request.expiresAt());
        } catch (DataIntegrityViolationException e) {
            throw new AliasAlreadyExistsException(request.customAlias(), e);
        }
    }

    private CreateUrlResponse toResponse(ShortUrl saved) {
        return new CreateUrlResponse(
                saved.getShortCode(),
                baseUrl + "/" + saved.getShortCode(),
                saved.getOriginalUrl(),
                saved.getCreatedAt(),
                saved.getExpiresAt());
    }
}
