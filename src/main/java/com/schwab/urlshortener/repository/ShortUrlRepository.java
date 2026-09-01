package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.entity.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByShortCode(String shortCode);

    @Modifying
    @Query("update ShortUrl s set s.clickCount = s.clickCount + 1, s.lastAccessedAt = :accessedAt where s.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode, @Param("accessedAt") Instant accessedAt);

    @Query(value = "SELECT nextval('short_code_seq')", nativeQuery = true)
    long nextShortCodeSequenceValue();


    @Modifying
    @Query("update ShortUrl s set s.active = false where s.shortCode = :shortCode")
    int deactivate(@Param("shortCode") String shortCode);
}
