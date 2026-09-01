# URL Shortener

A production-minded URL-shortening API built with Java, Spring Boot, and PostgreSQL. It creates
compact links, redirects visitors to their original destinations, supports custom aliases and
expiration, and records lightweight click analytics.

Built for the Schwab **AI-Assisted Software Engineering System** assignment, this repository
includes not only a runnable prototype but also its architectural reasoning, testing evidence,
trade-offs, and AI-collaboration traceability.

## Highlights

- Generated short codes and caller-defined aliases
- Optional URL expiration
- `302 Found` redirects
- Atomic click counts and last-accessed timestamps
- Collision-safe persistence with isolated retry transactions
- Flyway-managed PostgreSQL schema
- OpenAPI documentation and Swagger UI
- RFC 7807 error responses
- Unit, Spring wiring, and Testcontainers integration tests
- PMD, SpotBugs, JaCoCo, and optional dependency scanning
- Functional smoke and concurrent-load test scripts

## Stack

| Area | Technology |
|---|---|
| Runtime | Java 21, Spring Boot 4 |
| HTTP API | Spring MVC, Bean Validation, springdoc-openapi |
| Persistence | Spring Data JPA, Hibernate, PostgreSQL 16 |
| Schema | Flyway |
| Short codes | PostgreSQL sequence + Sqids; `SecureRandom` alternative |
| Testing | JUnit 5, Mockito, AssertJ, Testcontainers |
| Quality | JaCoCo, PMD, SpotBugs, OWASP Dependency-Check |
| Local runtime | Docker Compose |

## Quick start

### Prerequisites

- Java 21
- Maven 3.9+
- Docker with Compose

Create `.env` in the project root:

```dotenv
DB_NAME=urlshortener
DB_USER=app_user
DB_PASSWORD=app_pw_local_dev
DB_PORT=5432
```

Start PostgreSQL and the application:

```bash
./scripts/run.sh
```

The service starts at <http://localhost:8080>. Flyway applies all migrations automatically.

To run each component manually:

```bash
docker compose up -d
set -a && source .env && set +a
mvn spring-boot:run
```

If `5432` is already occupied, change `DB_PORT` in `.env`; Docker Compose and the application will
use the same value.

## API

### Create a short URL

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{
    "url": "https://example.com/articles/a-long-path",
    "expiresAt": "2027-01-01T00:00:00Z"
  }'
```

```json
{
  "shortCode": "UdBdLWZ",
  "shortUrl": "http://localhost:8080/UdBdLWZ",
  "originalUrl": "https://example.com/articles/a-long-path",
  "createdAt": "2026-09-01T16:00:00Z",
  "expiresAt": "2027-01-01T00:00:00Z"
}
```

To claim a custom alias:

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/docs","customAlias":"project-docs"}'
```

Aliases must contain 3–32 letters, numbers, underscores, or hyphens.

### Follow a short URL

```bash
curl -i http://localhost:8080/UdBdLWZ
```

The response is `302 Found` with the destination in its `Location` header.

### Read analytics

```bash
curl http://localhost:8080/api/v1/urls/UdBdLWZ/stats
```

```json
{
  "shortCode": "UdBdLWZ",
  "originalUrl": "https://example.com/articles/a-long-path",
  "createdAt": "2026-09-01T16:00:00Z",
  "expiresAt": "2027-01-01T00:00:00Z",
  "active": true,
  "clickCount": 1,
  "lastAccessedAt": "2026-09-01T16:05:00Z"
}
```

### Deactivate a short URL

```bash
curl -X DELETE http://localhost:8080/api/v1/urls/UdBdLWZ
```

Returns `204` and is idempotent — deactivating an already-inactive code still returns `204`. A
redirect to a deactivated code afterward returns `404`.

### Endpoint summary

| Method | Endpoint | Success | Purpose |
|---|---|---:|---|
| `POST` | `/api/v1/urls` | `201` | Create a short URL |
| `GET` | `/{shortCode}` | `302` | Redirect to the destination |
| `GET` | `/api/v1/urls/{shortCode}/stats` | `200` | Read click statistics |
| `DELETE` | `/api/v1/urls/{shortCode}` | `204` | Deactivate a short URL |

Errors use RFC 7807 `ProblemDetail`: `400` for malformed requests, `409` for an alias conflict,
`410` for an expired URL, `422` for an invalid destination, and `404` for an unknown code.

Interactive documentation is available while the application is running:

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI document: <http://localhost:8080/v3/api-docs>
- Health check: <http://localhost:8080/actuator/health>

For browsing without running the app: [`docs/openapi.json`](docs/openapi.json) is a point-in-time
snapshot of the generated spec, and a saved, self-contained copy of the Swagger UI page itself is
at [`docs/swagger-ui.html`](https://htmlpreview.github.io/?https://github.com/sasanktalluri/url_shortner/blob/main/docs/swagger-ui.html)
(rendered live via htmlpreview.github.io, since GitHub shows `.html` files as source rather than
rendering them). Both are generated artifacts, not hand-maintained, so they can drift from the
live app.

## Architecture

```text
Client
  ├─ POST /api/v1/urls
  │    └─ UrlController → UrlService → UrlValidator
  │                                ├─ generated → ShortCodeGenerator
  │                                └─ alias ─────→ ShortUrlWriter → PostgreSQL
  │
  ├─ GET /{shortCode}
  │    └─ RedirectController → RedirectService → PostgreSQL
  │                                             └─ atomic analytics update
  │
  └─ GET /api/v1/urls/{shortCode}/stats
       └─ UrlController → UrlService → PostgreSQL
```

### Short-code strategies

`ShortCodeGenerator` defines interchangeable generation strategies:

- **Sqids strategy (active):** encodes an atomic PostgreSQL sequence value. Generated inputs never
  repeat, so generated codes cannot collide with each other.
- **Random strategy:** draws seven alphanumeric characters using `SecureRandom` and retries rare
  uniqueness collisions.

Sqids produces compact, URL-safe identifiers, but it is encoding—not encryption. Codes must not
be treated as authorization secrets.

### Transaction and concurrency decisions

Every insertion attempt runs through `ShortUrlWriter` in its own transaction. This is important on
PostgreSQL because a constraint failure aborts its transaction; a retry must therefore start in a
fresh one.

Click tracking uses one atomic database statement:

```sql
SET click_count = click_count + 1
```

This avoids the lost-update problem caused by concurrent read-modify-write operations.

Redirects use `302`, not `301`, so browsers and intermediaries do not permanently cache mappings
that may later expire.

See [Architecture overview](docs/02-architecture-overview.md) for the complete design discussion.

## Testing

Run the full suite, including the real-PostgreSQL Testcontainers test:

```bash
mvn test
```

Docker must be running. Execute all configured quality gates with:

```bash
mvn verify
```

Run checks against a live application:

```bash
./scripts/api-smoke-test.sh
./scripts/load-test.sh 20 200
```

The smoke suite covers creation, aliases, validation, expiration, redirects, and analytics. The
load script exercises concurrent creation and redirects; it previously exposed a real connection-
pool exhaustion issue, which is documented in the testing notes.

Run the optional vulnerability scan separately because its initial NVD synchronization can be
slow:

```bash
mvn org.owasp:dependency-check-maven:check
```

See [Testing approach](docs/05-testing-approach.md) for coverage, validation evidence, and test
trade-offs.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `urlshortener` | Database name |
| `DB_USER` | `app_user` | Database user |
| `DB_PASSWORD` | `app_pw_local_dev` | Database password |
| `APP_BASE_URL` | `http://localhost:8080` | Prefix returned in short URLs |
| `SERVER_PORT` | `8080` | Application HTTP port |

`.env` is ignored by Git. Production deployments should inject secrets through their platform or
a dedicated secrets manager.

## Known limitations

- Analytics contains total clicks and last-accessed time, not an event history or daily,
  referrer, or geographic breakdown.
- Every redirect reads from and writes to PostgreSQL; there is no cache or asynchronous analytics
  pipeline.
- The currently implemented API has no authentication, authorization, or rate limiting.
- URL validation checks the HTTP/HTTPS scheme and host shape but does not fetch the destination.
- Sqids identifiers are reversible and are not a security boundary.

These are deliberate prototype trade-offs rather than production-readiness claims.

## Documentation

| Document | Contents |
|---|---|
| [Working prototype](docs/01-working-prototype.md) | End-to-end evidence and bugs found by running the service |
| [Architecture overview](docs/02-architecture-overview.md) | Components, control flow, strategies, and design decisions |
| [Three engineering scenarios](docs/03-three-scenarios.md) | Greenfield, brownfield, and ambiguous-requirement examples |
| [Setup instructions](docs/04-setup-instructions.md) | Detailed setup and troubleshooting |
| [Testing approach](docs/05-testing-approach.md) | Tests, load testing, quality gates, and trade-offs |
| [Engineering log](.agents/engineering-log.md) | AI-assisted decisions, reversals, and validation trail |

## Repository layout

```text
src/main/java/       Application code
src/main/resources/  Configuration and Flyway migrations
src/test/java/       Unit, wiring, and integration tests
docs/                Assignment deliverables and design documentation
scripts/             Run, smoke-test, and load-test helpers
.agents/             AI-collaboration traceability log
```

## License

This repository is an interview-assignment prototype. No open-source license has been declared.
