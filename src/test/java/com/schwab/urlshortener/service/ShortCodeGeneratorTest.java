package com.schwab.urlshortener.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ShortCodeGeneratorTest {
    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void generatesSevenCharacterAlphanumericCode() {
        String code = generator.generate();

        assertThat(code).hasSize(7);
        assertThat(code).matches("[A-Za-z0-9]{7}");
    }

    @Test
    void generatesDistinctCodesAcrossManyCalls() {
        Set<String> codes = new HashSet<>();
        IntStream.range(0, 1000).forEach(i -> codes.add(generator.generate()));

        assertThat(codes).hasSize(1000);
    }
}
