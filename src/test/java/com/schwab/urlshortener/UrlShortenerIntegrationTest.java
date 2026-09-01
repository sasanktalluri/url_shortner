package com.schwab.urlshortener;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.HttpURLConnection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack test against a real Postgres container: applies the Flyway migrations, creates a
 * short URL, follows the redirect, and checks that the click is recorded in stats.
 * Requires a local Docker daemon - run via `mvn test`.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UrlShortenerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("urlshortener")
            .withUsername("urlshortener")
            .withPassword("urlshortener");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    private RestTestClient client;

    @BeforeEach
    void setUp() {
        SimpleClientHttpRequestFactory nonFollowingFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };

        client = RestTestClient.bindToServer(nonFollowingFactory)
                .baseUrl("http://localhost:" + port)
                .build();
    }

    @Test
    void createRedirectAndTrackClickEndToEnd() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/very/long/path", null, null);

        CreateUrlResponse created = client.post().uri("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateUrlResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(created).isNotNull();
        String shortCode = created.shortCode();
        assertThat(shortCode).hasSize(7);

        client.get().uri("/" + shortCode)
                .exchange()
                .expectStatus().isFound()
                .expectHeader().location("https://example.com/very/long/path");

        UrlStatsResponse stats = client.get().uri("/api/v1/urls/" + shortCode + "/stats")
                .exchange()
                .expectStatus().isOk()
                .expectBody(UrlStatsResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(stats).isNotNull();
        assertThat(stats.clickCount()).isEqualTo(1);
        assertThat(stats.lastAccessedAt()).isNotNull();
    }

    @Test
    void returnsNotFoundForUnknownShortCode() {
        client.get().uri("/does-not-exist")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deactivateMakesTheCodeUnreachableAndIsIdempotent() {
        CreateUrlRequest request = new CreateUrlRequest("https://example.com/to-deactivate", null, null);
        CreateUrlResponse created = client.post().uri("/api/v1/urls")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateUrlResponse.class)
                .returnResult()
                .getResponseBody();
        String shortCode = created.shortCode();

        client.delete().uri("/api/v1/urls/" + shortCode)
                .exchange()
                .expectStatus().isEqualTo(204);

        // Idempotent: deactivating again still succeeds.
        client.delete().uri("/api/v1/urls/" + shortCode)
                .exchange()
                .expectStatus().isEqualTo(204);

        client.get().uri("/" + shortCode)
                .exchange()
                .expectStatus().isNotFound();

        client.delete().uri("/api/v1/urls/does-not-exist")
                .exchange()
                .expectStatus().isNotFound();
    }
}
