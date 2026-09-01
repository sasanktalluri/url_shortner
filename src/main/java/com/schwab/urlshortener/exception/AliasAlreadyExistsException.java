package com.schwab.urlshortener.exception;

public class AliasAlreadyExistsException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AliasAlreadyExistsException(String alias) {
        super("Short code already exists: " + alias);
    }

    public AliasAlreadyExistsException(String alias, Throwable cause) {
        super("Short code already exists: " + alias, cause);
    }
}
