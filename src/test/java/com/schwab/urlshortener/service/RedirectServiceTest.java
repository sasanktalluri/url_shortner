package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedirectServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ShortUrlRepository repository;
    @Mock
    private ShortUrlWriter writer;

    private RedirectService service;

    @BeforeEach
    void setUp() {
        service = new RedirectService(repository, writer, FIXED_CLOCK);
    }

    @Test
    void resolvesActiveUrlAndRecordsClick() {
        when(repository.findByShortCode("abc1234"))
                .thenReturn(Optional.of(new ShortUrl("abc1234", "https://example.com", NOW, null)));

        String url = service.resolve("abc1234");

        assertThat(url).isEqualTo("https://example.com");
        verify(writer).incrementClickCount("abc1234", NOW);
    }

    @Test
    void throwsNotFoundForUnknownShortCode() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("missing"))
                .isInstanceOf(UrlNotFoundException.class);
        verifyNoInteractions(writer);
    }

    @Test
    void throwsExpiredForPastExpiryDate() {
        when(repository.findByShortCode("abc1234"))
                .thenReturn(Optional.of(new ShortUrl("abc1234", "https://example.com", NOW, NOW.minusSeconds(1))));

        assertThatThrownBy(() -> service.resolve("abc1234"))
                .isInstanceOf(UrlExpiredException.class);
        verifyNoInteractions(writer);
    }
}
