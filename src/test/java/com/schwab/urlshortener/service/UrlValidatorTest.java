package com.schwab.urlshortener.service;

import com.schwab.urlshortener.exception.InvalidUrlException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {
    private final UrlValidator validator = new UrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com", "https://example.com/path?query=1"})
    void acceptsHttpAndHttpsUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.com", "javascript:alert(1)", "file:///etc/passwd"})
    void rejectsDisallowedSchemes(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertThatThrownBy(() -> validator.validate("http://")).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsMalformedUrl() {
        assertThatThrownBy(() -> validator.validate("http://[::1")).isInstanceOf(InvalidUrlException.class);
    }
}
