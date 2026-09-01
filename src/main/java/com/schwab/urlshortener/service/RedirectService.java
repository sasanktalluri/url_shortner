package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class RedirectService {
    private final ShortUrlRepository repository;
    private final Clock clock;

    @Autowired
    public RedirectService(ShortUrlRepository repository) {
        this(repository, Clock.systemUTC());
    }

    RedirectService(ShortUrlRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public String resolve(String shortCode) {
        ShortUrl shortUrl = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (!shortUrl.isActive()) {
            throw new UrlNotFoundException(shortCode);
        }

        if (shortUrl.getExpiresAt() != null && !shortUrl.getExpiresAt().isAfter(clock.instant())) {
            throw new UrlExpiredException(shortCode);
        }

        return shortUrl.getOriginalUrl();
    }
}
