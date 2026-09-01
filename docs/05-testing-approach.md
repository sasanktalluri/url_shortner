# Deliverable: Testing Approach, Limitations, and Trade-offs

## Automated tests

- **Unit tests** — `UrlValidatorTest`, `RandomShortCodeGeneratorTest`, `SqidsShortCodeGeneratorTest`
  (candidate shape, determinism, no-collision-across-many-calls, retry-on-collision),
  `UrlServiceTest` (alias vs. generated-code paths, expiry validation, stats lookup),
  `RedirectServiceTest` (active/expired/not-found branching, click tracking) — collaborators
  mocked via Mockito.

- **`ShortCodeGeneratorWiringTest`** — a real (mocked-DB) Spring context proving the `@Qualifier`
  wiring itself resolves correctly, not just that each generator class works in isolation.

- **Integration test** (`UrlShortenerIntegrationTest`) — `@SpringBootTest` with a real Postgres
  container via Testcontainers, exercised through actual HTTP calls: create → redirect → stats, a
  404 case, and deactivate — including that it's idempotent (deactivating twice still returns
  `204`) and that a redirect to a deactivated code returns `404`.

- All 28 tests pass locally (`mvn test`); jacoco reports **92% overall line coverage**
  (`target/site/jacoco/index.html` after running tests).

## Manual verification

The app itself was run end-to-end against a real Postgres container multiple times, not just
covered by automated tests.

- `curl` for create, redirect, stats, an unknown-code 404, an invalid-scheme 422, and deactivate
  (`204`, idempotent on a second call, `404` on redirect afterward).

- After the strategy-pattern rework: confirmed the live app still produces non-sequential
  Sqids-encoded codes for consecutive sequence values.

- `scripts/run.sh` run from a cold `docker compose down`, to confirm the automated setup path
  works, not just the manual steps.

- After adding `springdoc-openapi`: hit `/v3/api-docs` and `/swagger-ui/index.html` directly and
  checked the generated spec against what the controllers actually return. The first pass had
  springdoc defaulting error responses to `200`/the success schema — fixed with explicit
  `@ApiResponse` annotations (see [Architecture overview](02-architecture-overview.md)).

- Every request/response DTO field carries a `@Schema` description and example, and every path
  parameter a `@Parameter` description — checked directly in the generated `/v3/api-docs` JSON,
  not just assumed from the annotations being present in source.

## Load testing

Proper load testing is a JMeter/Gatling/k6 job — configurable ramp-up profiles, latency
percentiles, distributed load generation. None of that is set up here; instead two curl-based
scripts cover this project's actual needs at prototype scale.

- **`scripts/api-smoke-test.sh`** — every API combination: create with no alias, with a fresh
  alias, with an alias already taken (`409`), an invalid scheme (`422`), a malformed body (`400`),
  a past `expiresAt` (`400`), a URL that expires and then returns `410`, an unknown-code redirect
  (`404`), stats with a verified click-count increment, and stats for an unknown code (`404`). 12
  checks, all passing.

- **`scripts/load-test.sh [CONCURRENCY] [TOTAL]`** — fires `TOTAL` create requests, `CONCURRENCY`
  at a time via `xargs -P`, then does the same against redirect, reporting success rate + latency
  via curl's own `%{time_total}`.

**This found a real bug, not a hypothetical one.**

Running `./scripts/load-test.sh 20 200`: all 200 creates succeeded, but only 177/200 concurrent
redirects did — 23 failed with `500` after almost exactly 30 seconds each. The app log showed why:

```
HikariPool-1 - Connection is not available, request timed out after 30002ms (total=10, active=10, idle=0, waiting=19)
```

**Root cause:** `RedirectService.resolve()` was `@Transactional(readOnly = true)`, holding a
connection for the entire method. Inside it, `writer.incrementClickCount(...)` runs with
`@Transactional(propagation = REQUIRES_NEW)` — needed so the click-count write isn't rejected by
the outer read-only transaction (see [Architecture overview](02-architecture-overview.md)).
`REQUIRES_NEW` suspends the outer transaction rather than reusing it, so it needs a *second*
connection from the same pool while the first is still held.

Every redirect briefly needed 2 connections at once, so HikariCP's default pool of 10 structurally
saturated at 5 truly concurrent redirects — exactly what 20-way concurrency exposed.

**Fix:** removed `@Transactional(readOnly = true)` from `resolve()` itself. It wasn't actually
load-bearing there — `repository.findByShortCode(...)` already gets its own short read transaction
automatically, and `incrementClickCount()` already had its own explicit transaction. Without the
outer annotation, each DB call acquires and releases its own connection *sequentially* instead of
the method holding one open while a second is grabbed on top of it — same correctness, half the
peak connection pressure per request.

**Validated the fix, not just applied it:** re-ran the same test — 200/200 redirects succeeded,
average latency dropped from ~6s (dominated by timeouts) to 20ms. Pushed further to 400 requests
at 40 concurrent (4x the pool size) — still 0 failures. Full `mvn verify` still passes after the
change.

## Quality gates

Beyond `mvn test`, `mvn verify` runs three more checks, and one more is available on demand.

- **PMD** (`pmd-ruleset.xml`) — catches real defects, not just style. First run found 11
  violations: 8 were genuine and fixed (missing `serialVersionUID` on 4 exception classes, two
  places throwing a new exception without chaining the original cause, a missing `Locale` on a
  case-insensitive comparison, a missing `@FunctionalInterface` marker). 3 were false positives
  for intentional patterns — excluded centrally in `pmd-ruleset.xml`, not suppressed in source;
  see [Architecture overview](02-architecture-overview.md#key-decisions).

- **SpotBugs** (bytecode-level, effort `Max`, threshold `Medium`) — zero findings.

- **jacoco** — coverage report, not gated on a minimum threshold (see Limitations below).

- **OWASP `dependency-check-maven`** — dependency vulnerability scan against the NVD database.
  Not bound to the default lifecycle: it syncs ~385K NVD records over the network on first run,
  which can take a long time without an API key. Run explicitly:
  `mvn org.owasp:dependency-check-maven:check`.

## Limitations and trade-offs

- Analytics is intentionally minimal (total clicks + last access) — no per-day/referrer/geo
  breakdown; see [Three scenarios](03-three-scenarios.md#3-ambiguous--scoping-analytics).

- No auth/rate-limiting on `POST /api/v1/urls`, or on deactivate. Anyone who knows a code can
  currently deactivate it. Not in the assignment's stated requirements, and a real auth model
  wasn't specified either — building one would be guessing at a requirement nobody stated, the
  same judgment call as Scenario 3.

- No caching layer in front of Postgres — every redirect is a DB read plus a DB write. Fine at
  prototype scale; a hot-path cache would be the first thing to add if redirect volume became the
  bottleneck.

- `SqidsShortCodeGenerator` does one DB round trip per code created. A block/range allocator
  would cut that to one round trip per N codes, but adds concurrent, stateful logic that needs its
  own dedicated tests — deliberately not built since the plain sequence isn't a bottleneck today.

- `UrlValidator` checks scheme/host shape only; it doesn't block redirects to loopback/link-local
  targets. Low risk since the server never fetches the target (it 302s the browser), but worth
  revisiting if this ever grows a fetch-based feature.

- Collision retry is bounded at 5 attempts per strategy — astronomically unlikely to be exhausted
  for the random strategy, and a ceiling on custom-alias clashes only for the Sqids strategy.

- jacoco doesn't enforce a minimum via `jacoco:check`, on purpose: a fixed threshold is easy to
  game and doesn't mean the tests that matter exist. 92% achieved organically is a stronger signal.

- Load testing is a curl-based approximation, not a real load-testing tool — no ramp-up profile,
  no latency percentiles. It's what found and validated the fix for the connection-pool
  exhaustion bug above, but a proper JMeter/k6 setup would give a clearer picture under sustained
  load.
