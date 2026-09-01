package com.schwab.urlshortener.exception;

public class UrlExpiredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UrlExpiredException(String shortCode) {
        super("Short URL has expired: " + shortCode);
    }
}
