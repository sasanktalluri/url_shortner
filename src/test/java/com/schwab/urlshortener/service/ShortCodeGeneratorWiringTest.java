package com.schwab.urlshortener.service;

import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies the @Qualifier wiring itself, not just that each generator class works in isolation:
 * loads a real (minimal) Spring context so a broken qualifier name, a missing bean, or an
 * ambiguous injection point would fail here the same way it would in the full application - no
 * database needed, since ShortUrlRepository is mocked.
 */
@SpringJUnitConfig(ShortCodeGeneratorWiringTest.TestConfig.class)
class ShortCodeGeneratorWiringTest {

    @Autowired
    @Qualifier("sqids")
    private ShortCodeGenerator sqidsGenerator;

    @Autowired
    @Qualifier("random")
    private ShortCodeGenerator randomGenerator;

    @Test
    void sqidsQualifierResolvesToSqidsImplementation() {
        assertThat(sqidsGenerator).isInstanceOf(SqidsShortCodeGenerator.class);
    }

    @Test
    void randomQualifierResolvesToRandomImplementation() {
        assertThat(randomGenerator).isInstanceOf(RandomShortCodeGenerator.class);
    }

    @Configuration
    @Import({SqidsShortCodeGenerator.class, RandomShortCodeGenerator.class, ShortUrlWriter.class})
    static class TestConfig {
        @Bean
        ShortUrlRepository shortUrlRepository() {
            return mock(ShortUrlRepository.class);
        }
    }
}
