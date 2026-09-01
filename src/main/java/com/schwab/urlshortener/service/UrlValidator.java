package com.schwab.urlshortener.service;

import com.schwab.urlshortener.exception.InvalidUrlException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

@Component
public class UrlValidator {
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    public void validate(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new InvalidUrlException("Only http and https URLs are supported");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new InvalidUrlException("URL must contain a valid host");
            }
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("URL is malformed", e);
        }
    }
}
