package com.schwab.urlshortener.service;

import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.sqids.Sqids;

import java.time.Instant;
import java.util.List;

/**
 * Takes the next value of a Postgres sequence (short_code_seq) and encodes it with Sqids
 * (sqids.org). nextval() on a Postgres sequence is atomic and never repeats a value - even
 * across concurrent callers or a rolled-back transaction - so a candidate here can never clash
 * with another candidate this generator has produced.
 * <p>
 * Encoding through Sqids rather than emitting the raw counter (or the counter left-padded into a
 * string) matters because a raw counter is sequential and therefore enumerable - anyone could
 * walk shortUrl/100001, /100002, ... and discover every link ever created. Sqids is a bijection
 * (a shuffled-alphabet reversible mapping): every counter value still maps to exactly one output
 * and vice versa, so the uniqueness guarantee from the sequence is preserved, but the output does
 * not reveal the underlying order.
 * <p>
 * The retry loop below still exists, and is not dead code: a generated candidate can still clash
 * with a pre-existing *custom alias*, since aliases and generated codes share one uniqueness
 * namespace and an alias is an arbitrary user-chosen string. Retrying is safe because each
 * generateCandidate() call advances the sequence, so a retry never reproduces the same candidate.
 * <p>
 * Qualified "sqids": UrlService asks for this strategy by name (@Qualifier("sqids")) rather than
 * relying on @Primary, so which strategy is wired in is explicit at the injection point instead
 * of an implicit default. This is the "optimal" choice noted in the assignment write-up -
 * collision-free against itself. RandomShortCodeGenerator (qualifier "random") remains available
 * as the strategy pattern's other implementation.
 */
@Component
@Qualifier("sqids")
class SqidsShortCodeGenerator implements ShortCodeGenerator {
    private static final int MIN_LENGTH = 7;
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    private final ShortUrlRepository repository;
    private final ShortUrlWriter writer;
    private final Sqids sqids;

    @Autowired
    SqidsShortCodeGenerator(ShortUrlRepository repository, ShortUrlWriter writer) {
        this(repository, writer, Sqids.builder().minLength(MIN_LENGTH).build());
    }

    SqidsShortCodeGenerator(ShortUrlRepository repository, ShortUrlWriter writer, Sqids sqids) {
        this.repository = repository;
        this.writer = writer;
        this.sqids = sqids;
    }

    @Override
    public ShortUrl createShortUrl(String originalUrl, Instant now, Instant expiresAt) {
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                return writer.save(generateCandidate(), originalUrl, now, expiresAt);
            } catch (DataIntegrityViolationException collision) {
                if (attempt == MAX_GENERATION_ATTEMPTS) {
                    throw collision;
                }
            }
        }
        throw new IllegalStateException("Unable to generate a unique short code");
    }

    private String generateCandidate() {
        long sequenceValue = repository.nextShortCodeSequenceValue();
        return sqids.encode(List.of(sequenceValue));
    }
}
