package com.schwab.urlshortener.exception;

public class UrlNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UrlNotFoundException(String shortCode) {
        super("Short URL not found: " + shortCode);
    }
}
