package com.schwab.urlshortener.exception;

public class AliasAlreadyExistsException extends RuntimeException {
    public AliasAlreadyExistsException(String alias) {
        super("Short code already exists: " + alias);
    }
}
