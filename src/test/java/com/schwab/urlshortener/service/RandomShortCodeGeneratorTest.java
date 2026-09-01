package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RandomShortCodeGeneratorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private ShortUrlWriter writer;

    private RandomShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new RandomShortCodeGenerator(writer);
    }

    @Test
    void createsShortUrlWithSevenCharacterAlphanumericCode() {
        when(writer.save(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> new ShortUrl(invocation.getArgument(0), "https://example.com", NOW, null));

        ShortUrl result = generator.createShortUrl("https://example.com", NOW, null);

        assertThat(result.getShortCode()).hasSize(7);
        assertThat(result.getShortCode()).matches("[A-Za-z0-9]{7}");
    }

    @Test
    void retriesWithANewCandidateOnCollision() {
        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        when(writer.save(codes.capture(), anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"))
                .thenAnswer(invocation -> new ShortUrl(invocation.getArgument(0), "https://example.com", NOW, null));

        ShortUrl result = generator.createShortUrl("https://example.com", NOW, null);

        assertThat(result.getShortCode()).isEqualTo(codes.getAllValues().get(1));
        assertThat(codes.getAllValues().get(0)).isNotEqualTo(codes.getAllValues().get(1));
        verify(writer, times(2)).save(anyString(), anyString(), any(), any());
    }

    @Test
    void throwsAfterExhaustingAllAttempts() {
        when(writer.save(anyString(), anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> generator.createShortUrl("https://example.com", NOW, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(writer, times(5)).save(anyString(), anyString(), any(), any());
    }
}
