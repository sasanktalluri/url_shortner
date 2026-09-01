package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UrlServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ShortUrlRepository repository;
    @Mock
    private ShortUrlWriter writer;
    @Mock
    private ShortCodeGenerator generator;
    @Mock
    private UrlValidator validator;

    private UrlService service;

    @BeforeEach
    void setUp() {
        service = new UrlService(repository, writer, generator, validator, FIXED_CLOCK, "http://short.ly");
    }

    @Test
    void createsUrlViaGeneratorWhenNoAliasGiven() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, null);
        when(generator.createShortUrl("https://example.com", NOW, null))
                .thenReturn(new ShortUrl("abc1234", "https://example.com", NOW, null));

        CreateUrlResponse response = service.create(request);

        assertThat(response.shortCode()).isEqualTo("abc1234");
        assertThat(response.shortUrl()).isEqualTo("http://short.ly/abc1234");
        verify(validator).validate("https://example.com");
        verifyNoInteractions(writer);
    }

    @Test
    void usesCustomAliasWhenProvided() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "my-alias", null);
        when(writer.save("my-alias", "https://example.com", NOW, null))
                .thenReturn(new ShortUrl("my-alias", "https://example.com", NOW, null));

        CreateUrlResponse response = service.create(request);

        assertThat(response.shortCode()).isEqualTo("my-alias");
        verifyNoInteractions(generator);
    }

    @Test
    void throwsAliasAlreadyExistsWhenCustomAliasCollides() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", "taken", null);
        when(writer.save(eq("taken"), anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void rejectsExpiresAtInThePast() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com", null, NOW.minusSeconds(60));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(writer, generator);
    }

    @Test
    void returnsStatsForExistingShortCode() {
        ShortUrl shortUrl = new ShortUrl("abc1234", "https://example.com", NOW, null);
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(shortUrl));

        UrlStatsResponse stats = service.getStats("abc1234");

        assertThat(stats.shortCode()).isEqualTo("abc1234");
        assertThat(stats.clickCount()).isZero();
    }

    @Test
    void throwsNotFoundForUnknownShortCodeStats() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStats("missing"))
                .isInstanceOf(UrlNotFoundException.class);
    }
}
