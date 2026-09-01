package com.schwab.urlshortener.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class ShortCodeGenerator {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int DEFAULT_LENGTH = 7;

    private final SecureRandom secureRandom;

    public ShortCodeGenerator() {
        this(new SecureRandom());
    }

    ShortCodeGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        StringBuilder result = new StringBuilder(DEFAULT_LENGTH);
        for (int i = 0; i < DEFAULT_LENGTH; i++) {
            result.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }
}
