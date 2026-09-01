package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.sqids.Sqids;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqidsShortCodeGeneratorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Sqids SQIDS = Sqids.builder().minLength(7).build();

    @Mock
    private ShortUrlRepository repository;
    @Mock
    private ShortUrlWriter writer;

    private SqidsShortCodeGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SqidsShortCodeGenerator(repository, writer, SQIDS);
    }

    @Test
    void createsShortUrlAtLeastMinLengthLong() {
        when(repository.nextShortCodeSequenceValue()).thenReturn(100000L);
        when(writer.save(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> new ShortUrl(invocation.getArgument(0), "https://example.com", NOW, null));

        ShortUrl result = generator.createShortUrl("https://example.com", NOW, null);

        assertThat(result.getShortCode()).hasSizeGreaterThanOrEqualTo(7);
        assertThat(result.getShortCode()).matches("[A-Za-z0-9]+");
    }

    @Test
    void distinctSequenceValuesNeverCollideAcrossManyCalls() {
        Set<String> codes = new HashSet<>();
        for (long value = 100000; value < 101000; value++) {
            when(repository.nextShortCodeSequenceValue()).thenReturn(value);
            when(writer.save(anyString(), anyString(), any(), any()))
                    .thenAnswer(invocation -> new ShortUrl(invocation.getArgument(0), "https://example.com", NOW, null));

            codes.add(generator.createShortUrl("https://example.com", NOW, null).getShortCode());
        }

        assertThat(codes).hasSize(1000);
    }

    @Test
    void retriesWithTheNextSequenceValueOnAliasCollision() {
        when(repository.nextShortCodeSequenceValue()).thenReturn(100000L, 100001L);
        when(writer.save(anyString(), anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("clashes with an existing custom alias"))
                .thenAnswer(invocation -> new ShortUrl(invocation.getArgument(0), "https://example.com", NOW, null));

        ShortUrl result = generator.createShortUrl("https://example.com", NOW, null);

        assertThat(result.getShortCode()).isEqualTo(SQIDS.encode(java.util.List.of(100001L)));
        verify(repository, times(2)).nextShortCodeSequenceValue();
    }

    @Test
    void throwsAfterExhaustingAllAttempts() {
        when(repository.nextShortCodeSequenceValue()).thenReturn(100000L, 100001L, 100002L, 100003L, 100004L);
        when(writer.save(anyString(), anyString(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(() -> generator.createShortUrl("https://example.com", NOW, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(writer, times(5)).save(anyString(), anyString(), any(), any());
    }
}
