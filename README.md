# URL Shortener

A Spring Boot URL shortener with create/redirect APIs and click analytics, built as the Schwab
"AI-Assisted Software Engineering System" interview assignment
(`docs/010 - Assignment - AI-Proficient Software Engineer.pdf`). See `.agents/` for the full
AI-collaboration traceability log (decisions, rejected approaches, rationale) behind this repo.

Short-code generation is built as a **Strategy pattern** — see
[Strategy pattern: short-code generation](#strategy-pattern-short-code-generation) below.

## Setup

**Prerequisites:** Java 21, Maven, Docker.

```bash
./scripts/run.sh
```
Starts Postgres via `docker compose`, waits for it to be healthy, then runs the app (Flyway
migrations apply automatically on startup). Or do it by hand:

```bash
docker compose up -d       # start Postgres
mvn spring-boot:run        # run the app
```

Try it:
```bash
curl -X POST localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com/some/long/path"}'
# => {"shortCode":"UdBdLWZ","shortUrl":"http://localhost:8080/UdBdLWZ", ...}

curl -i localhost:8080/UdBdLWZ                       # 302 redirect
curl localhost:8080/api/v1/urls/UdBdLWZ/stats        # click count / last accessed
```

`.env` (git-ignored) needs:
```
DB_NAME=urlshortener
DB_USER=app_user
DB_PASSWORD=app_pw_local_dev
DB_PORT=5432
```
If port 5432 is already taken by another local Postgres, change `DB_PORT` there (`docker-compose.yml`
reads it for both the container's published port and the healthcheck) and pass the same value to
the app, e.g. `DB_PORT=5433 mvn spring-boot:run` (`scripts/run.sh` picks up whatever's in `.env`
automatically either way).

Full list of env vars the app reads (see `src/main/resources/application.yml`): `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`, `DB_PASSWORD`, `APP_BASE_URL`, `SERVER_PORT`.

## Architecture

```
Client
  │
  ├─ POST /api/v1/urls ─────────────► UrlController ─► UrlService ──► UrlValidator
  │                                                       │              (scheme/host checks)
  │                                                       ├─(no alias)─► ShortCodeGenerator ──► ShortUrlWriter ──► Postgres
  │                                                       └─(alias)────► ShortUrlWriter ──► Postgres
  │
  ├─ GET /{shortCode} ───────────────► RedirectController ─► RedirectService ──► Postgres (read)
  │                                                             └──► ShortUrlWriter.incrementClickCount ──► Postgres
  │
  └─ GET /api/v1/urls/{c}/stats ─────► UrlController ─► UrlService ──► Postgres (direct read)
```

**Components**
- `UrlController` / `RedirectController` — HTTP boundary; validation via `@Valid` + Bean
  Validation annotations on `CreateUrlRequest`.
- `UrlService` — orchestrates create (custom-alias path vs. generated-code path) and stats lookup.
  Depends on `ShortCodeGenerator` only for the generated-code path; a custom alias goes straight
  through `ShortUrlWriter`, since "this alias is taken" is a conflict to report, not something to
  retry.
- `ShortCodeGenerator` — the Strategy interface for short-code generation; see
  [Strategy pattern: short-code generation](#strategy-pattern-short-code-generation) below.
- `ShortUrlWriter` — the only component that writes to Postgres; every write method is its own
  transaction (`@Transactional(propagation = REQUIRES_NEW)`) so one failed attempt can't poison
  another (see the retry-bug note below).
- `GlobalExceptionHandler` — maps domain exceptions to RFC 7807 `ProblemDetail` responses; a
  catch-all handler returns a generic 500 instead of leaking internal exception messages.
- Flyway (`db/migration/V1`–`V3`) owns the schema; JPA runs with `ddl-auto: validate` so
  application startup fails loudly if the entity and the migrated schema disagree.

## Strategy pattern: short-code generation

Short-code generation is a textbook **Strategy pattern**: a single interface,
`ShortCodeGenerator`, with interchangeable algorithms behind it, and the caller (`UrlService`)
coded only against the interface — it has no idea which concrete algorithm is running.

```
                 ShortCodeGenerator            «interface»
                 + createShortUrl(originalUrl, now, expiresAt): ShortUrl
                         ▲              ▲
                         │              │
        implements ──────┘              └────── implements
                         │              │
     SqidsShortCodeGenerator      RandomShortCodeGenerator
     @Qualifier("sqids")          @Qualifier("random")
     (default - wired into        (available, not wired into
      UrlService)                  UrlService by default)

UrlService(@Qualifier("sqids") ShortCodeGenerator generator, ...)
   → depends only on the interface; swapping strategies is a one-line
     change to the @Qualifier value, no change to UrlService's logic.
```

`ShortCodeGenerator` (`createShortUrl(originalUrl, now, expiresAt) → ShortUrl`) has two
implementations, selected by Spring `@Qualifier` rather than `@Primary` — the active strategy is
explicit at `UrlService`'s injection point instead of an implicit default:

- **`SqidsShortCodeGenerator`** (`@Qualifier("sqids")`, the one `UrlService` uses) — takes the next
  value of a Postgres sequence (`short_code_seq`, `V3` migration) and encodes it with
  [Sqids](https://sqids.org/) (`org.sqids:sqids`), a proven reversible-mapping library. A sequence
  value is atomic and never repeats, so this strategy cannot collide against its own output.
  Encoding through Sqids rather than emitting the raw counter matters because a raw counter is
  sequential and enumerable — anyone could walk `/100001`, `/100002`, ... and discover every link
  ever created; Sqids' shuffled-alphabet bijection preserves the uniqueness guarantee from the
  sequence while making the output not reveal the underlying order.
- **`RandomShortCodeGenerator`** (`@Qualifier("random")`, available but not wired into `UrlService`
  by default) — draws 7 characters uniformly at random via `SecureRandom` (not `java.util.Random`:
  a short code is a bearer credential, so it must not be predictable from prior outputs the way a
  plain LCG-based `Random`'s sequence can be).

**Why a Strategy here specifically:** the two algorithms have genuinely different trade-offs
(collision-free-by-construction and enumeration-resistant vs. simpler and not DB-dependent for the
candidate itself) with no clearly-always-right answer, and the choice needed to be swappable
without touching `UrlService`, `ShortUrlWriter`, or any caller. `ShortCodeGeneratorWiringTest`
exists specifically to prove that swap is safe — it fails loudly on a bad `@Qualifier` name, a
missing bean, or an ambiguous injection point, independent of whether either strategy's own logic
is correct.

Each implementation owns its own collision-retry loop (up to 5 attempts), rather than that logic
living in `UrlService` — whether retrying is meaningful, and why a collision could even happen,
depends entirely on the strategy: for `RandomShortCodeGenerator` a clash with another generated
code is possible, if rare (62⁷ ≈ 3.5×10¹² possible codes — negligible at any realistic scale, but
cheap to retry regardless). For `SqidsShortCodeGenerator` a clash with another *generated* code is
structurally impossible, but a clash with a pre-existing *custom alias* is still possible — aliases
and generated codes share one uniqueness namespace, and an alias is an arbitrary user-chosen
string. Retrying is safe there too, since each candidate advances the sequence, so a retry never
reproduces the same candidate.

**Key decisions**
- *Click counting is a single atomic `UPDATE ... SET click_count = click_count + 1`*, not a
  read-modify-write on the loaded entity, to avoid lost updates under concurrent redirects to the
  same code. Verified manually: three concurrent-ish redirects to the same code landed exactly
  `clickCount: 3`, not less.
- *302 (Found) redirects*, not 301, so browsers/CDNs don't permanently cache a mapping that could
  later expire.
- *No caching layer* in front of Postgres — every redirect is a DB read plus a DB write. Simplest
  correct option at prototype scale; the first thing to add if redirect volume became the
  bottleneck.

## Bugs found by actually running the app

Two real defects only surfaced by building and running the app end-to-end (compiling and unit
testing alone didn't catch either):

1. **Spring Boot 4 moved Flyway autoconfiguration into its own module** (`spring-boot-flyway`) -
   having `flyway-core` and `flyway-database-postgresql` on the classpath was not sufficient; the
   app booted but Hibernate tried to validate against a schema Flyway had never touched, and failed
   with "missing table [short_urls]". Fixed by adding `spring-boot-flyway` as an explicit
   dependency.
2. **`RedirectService`/`UrlService` failed to boot** - both have a public constructor plus a
   package-private one used only by tests, and neither was marked `@Autowired`. Spring Framework 7
   won't pick a constructor automatically when there's more than one candidate; it fell back to
   looking for a no-arg constructor, found none, and threw `NoSuchMethodException`. Fixed by adding
   `@Autowired` to the intended constructor in both classes - and caught the same pattern again in
   `SqidsShortCodeGenerator` before it shipped, by checking for it deliberately this time.

Also required bumping the pinned Testcontainers version from `1.21.3` to `1.21.4` - the older
version's Docker client couldn't negotiate with this machine's Docker Desktop version and failed
with a `Could not find a valid Docker environment` error even though `docker info`/`docker ps`
worked fine from the shell.

## Three scenarios

**1) Greenfield — click analytics.** The original prototype had create + redirect but no
analytics, despite the brief asking for "core APIs, analytics, and reliability features."
Decomposed as: add `click_count`/`last_accessed_at` columns (`V2` migration) → atomic increment
query on the repository → wire the increment into `RedirectService.resolve` → add
`GET /api/v1/urls/{shortCode}/stats`. Validated with unit tests, the Testcontainers integration
test, and a manual run (3 redirects → `clickCount: 3`).

**2) Brownfield — fixing the collision-retry bug.** The original `UrlService.create()` retried
short-code generation up to 5 times on a unique-constraint collision, catching
`DataIntegrityViolationException` inside what was originally one `@Transactional` method. On
Postgres, a failed statement aborts the *entire* transaction — every retry after the first
collision would fail with `25P02: current transaction is aborted`, not actually retry. Root cause:
the transactional boundary was at the wrong level (the whole `create()` call) instead of
per-attempt. Fix: extracted the write into `ShortUrlWriter.save()`, its own bean with
`@Transactional(propagation = REQUIRES_NEW)`, so each generation attempt is an independent
transaction. Validated by a regression test forcing a first-attempt collision and asserting the
second attempt succeeds (now `RandomShortCodeGeneratorTest.retriesWithANewCandidateOnCollision`).

**3) Ambiguous — scoping "analytics."** The brief says "analytics" with no further spec: per-day
breakdowns? referrer/geo tracking? real-time dashboards? Given the assignment's timebox and no
stakeholder to ask, the interpretation taken was the smallest analytics surface that's still
genuinely useful: total click count + last-accessed timestamp per short URL, exposed via a stats
endpoint. Rejected alternatives and why: a per-click event log (unbounded table growth, no
consumer for the data yet) and a background/async click pipeline (adds a queue/worker for a
feature that's currently low-volume and needs strong consistency with create/redirect, not
eventual consistency).

## Testing approach

- **Unit tests** — `UrlValidatorTest`, `RandomShortCodeGeneratorTest`, `SqidsShortCodeGeneratorTest`
  (candidate shape, determinism, no-collision-across-many-calls, retry-on-collision), `UrlServiceTest`
  (alias vs. generated-code paths, expiry validation, stats lookup), `RedirectServiceTest`
  (active/expired/not-found branching, click tracking) — collaborators mocked via Mockito.
- **`ShortCodeGeneratorWiringTest`** — a real (mocked-DB) Spring context proving the `@Qualifier`
  wiring itself resolves correctly (`"sqids"` → `SqidsShortCodeGenerator`, `"random"` →
  `RandomShortCodeGenerator`), not just that each generator class works in isolation.
- **Integration test** (`UrlShortenerIntegrationTest`) — `@SpringBootTest` with a real Postgres
  container via Testcontainers, all Flyway migrations applied for real, exercised through actual
  HTTP calls (`RestTestClient`): create → redirect → stats, plus a 404 case.
- All 27 tests pass locally (`mvn test`); jacoco reports **92% overall line coverage**
  (`target/site/jacoco/index.html` after running tests).
- The app itself was run end-to-end against a real Postgres container multiple times and manually
  exercised with `curl` for create, redirect, stats, an unknown-code 404, an invalid-scheme 422,
  and (after the strategy-pattern rework) confirmed the live app still produces non-sequential
  Sqids-encoded codes — not just covered by automated tests.

## Limitations and trade-offs

- Analytics is intentionally minimal (total clicks + last access) — no per-day/referrer/geo
  breakdown; see Scenario 3.
- No auth/rate-limiting on `POST /api/v1/urls` — anyone can mint short codes or exhaust the
  keyspace; acceptable for a prototype, not for production.
- No deactivate/delete API.
- No caching layer in front of Postgres — every redirect is a DB read plus a DB write. Fine at
  prototype scale; a hot-path cache (e.g. Redis, cache-aside, short TTL) would be the first thing
  to add if redirect volume became the bottleneck.
- `SqidsShortCodeGenerator` does one DB round trip (`nextval()`) per code created. Fine at
  prototype scale; a block/range allocator (fetch a batch of sequence values at once, hand them out
  from memory, matching Hibernate's `hi/lo` sequence optimizer pattern) would cut that to one round
  trip per N codes if generation throughput ever became a bottleneck. Deliberately not built now —
  it adds concurrent, stateful logic (in-memory counter, refill-on-exhaustion, thread-safety across
  racing refills) that needs its own dedicated concurrency tests to trust, and the plain sequence
  isn't a bottleneck today.
- `UrlValidator` checks scheme/host shape only; it doesn't block redirects to loopback/link-local
  targets. Low risk here since the server never fetches the target (it 302s the browser), but
  worth revisiting if this ever grows a URL-preview or unfurling feature that *does* fetch
  server-side.
- Collision retry is bounded at 5 attempts per strategy; for the random strategy this is an
  astronomically unlikely-to-be-exhausted but real ceiling given the 62⁷ code space, for the Sqids
  strategy it's a ceiling on custom-alias clashes only.
