# Deliverable: Architecture Overview

## Components, tools, control flow

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

**Stack / tools:** Spring Boot 4.1.1 (Spring Framework 7, Java 21), Postgres via Flyway-managed
schema, JPA/Hibernate, `org.sqids:sqids` for short-code encoding, `springdoc-openapi` for the
generated API spec, Testcontainers for the integration test, PMD + SpotBugs as static-analysis
quality gates, OWASP `dependency-check-maven` for dependency vulnerability scanning, jacoco for
coverage, Docker/`docker-compose` for local Postgres.

**Components**
- `UrlController` / `RedirectController` — HTTP boundary; validation via `@Valid` + Bean
  Validation annotations on `CreateUrlRequest`; annotated with `@Operation`/`@ApiResponse` so the
  generated OpenAPI spec reports accurate status codes and response schemas (see
  [Testing approach](05-testing-approach.md) for why that needed fixing after adding springdoc).
- `UrlService` — orchestrates create (custom-alias path vs. generated-code path) and stats lookup.
  Depends on `ShortCodeGenerator` only for the generated-code path; a custom alias goes straight
  through `ShortUrlWriter`, since "this alias is taken" is a conflict to report, not something to
  retry.
- `ShortCodeGenerator` — the Strategy interface for short-code generation; see below.
- `ShortUrlWriter` — the only component that writes to Postgres; every write method is its own
  transaction (`@Transactional(propagation = REQUIRES_NEW)`) so one failed attempt can't poison
  another (see the retry-bug note in [Three scenarios](03-three-scenarios.md)).
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

## Key decisions

- *Click counting is a single atomic `UPDATE ... SET click_count = click_count + 1`*, not a
  read-modify-write on the loaded entity, to avoid lost updates under concurrent redirects to the
  same code. Verified manually: three concurrent-ish redirects to the same code landed exactly
  `clickCount: 3`, not less.
- *302 (Found) redirects*, not 301, so browsers/CDNs don't permanently cache a mapping that could
  later expire.
- *No caching layer* in front of Postgres — every redirect is a DB read plus a DB write. Simplest
  correct option at prototype scale; the first thing to add if redirect volume became the
  bottleneck.
- *Static analysis exclusions live in `pmd-ruleset.xml`, not `@SuppressWarnings` in source.* PMD
  flags three real false positives for patterns this codebase intentionally uses (a JPA entity
  looking like a "data class," a Spring Boot main class looking like a utility class, a one-level
  getter call looking like a Law of Demeter violation) — excluded centrally in the ruleset config
  with the reasoning written down there, rather than scattered across source files.

## Execution approach (AI-assisted)

Built interactively with Claude Code: the engineer reviewed the skeleton against the assignment
brief, directed each feature/fix, and approved or rejected every non-trivial design choice the
agent proposed (see `.agents/engineering-log.md` for the specific rejections and why — e.g. a
proposed `ShortCodeAllocator` component was rejected in favor of each generator owning its own
retry logic). Every risky action (force-pushes, git-history rewrites, installing Docker, credential
choices) was confirmed before execution. The app was built and actually run end-to-end repeatedly,
not just compiled and unit-tested — that's what surfaced both real bugs documented in
[Three scenarios](03-three-scenarios.md) and the [Working prototype](01-working-prototype.md) doc.
