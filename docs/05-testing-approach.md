# Deliverable: Testing Approach, Limitations, and Trade-offs

## Automated tests

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

## Manual verification

The app itself was run end-to-end against a real Postgres container multiple times, not just
covered by automated tests:
- `curl` for create, redirect, stats, an unknown-code 404, an invalid-scheme 422.
- After the strategy-pattern rework: confirmed the live app still produces non-sequential
  Sqids-encoded codes for consecutive sequence values.
- `scripts/run.sh` itself was run from a cold `docker compose down` to confirm the automated setup
  path works, not just the manual steps.
- After adding `springdoc-openapi`: hit `/v3/api-docs` and `/swagger-ui/index.html` directly and
  checked the generated spec's response codes/schemas against what the controllers actually
  return — the first pass had springdoc defaulting error responses to `200`/the success schema,
  which was wrong and got fixed with explicit `@ApiResponse` annotations (see
  [Architecture overview](02-architecture-overview.md)).

## Load testing

Proper load testing is a JMeter/Gatling/k6 job — configurable ramp-up profiles, latency
percentiles (p50/p95/p99), distributed load generation. None of that is set up here; instead
there are two curl-based scripts that cover this project's actual needs at prototype scale:

- **`scripts/api-smoke-test.sh`** — functional coverage of every API combination: create with no
  alias, with a fresh custom alias, with an alias that's already taken (expects `409`), an invalid
  URL scheme (`422`), a malformed body (`400`), an already-past `expiresAt` (`400`), a URL that's
  created short-lived and then actually expires (`410` on redirect), redirect to an unknown code
  (`404`), stats for a known code (checks `clickCount` actually incremented from the redirect
  above), and stats for an unknown code (`404`). 12 checks, all passing.
- **`scripts/load-test.sh [CONCURRENCY] [TOTAL]`** — fires `TOTAL` create requests `CONCURRENCY`
  at a time via `xargs -P`, then does the same against redirect for every code just created, and
  reports success rate + latency (via curl's own `%{time_total}`, not external timing).

**This found a real bug, not a hypothetical one.** Running `./scripts/load-test.sh 20 200` against
the live app: all 200 creates succeeded, but only 177/200 concurrent redirects did — 23 failed
with `500` after almost exactly 30 seconds each. The app log showed why:
```
HikariPool-1 - Connection is not available, request timed out after 30002ms (total=10, active=10, idle=0, waiting=19)
```
**Root cause:** `RedirectService.resolve()` was `@Transactional(readOnly = true)`, which holds a
connection for the entire method. Inside it, `writer.incrementClickCount(...)` runs with
`@Transactional(propagation = REQUIRES_NEW)` (needed so the click-count write isn't rejected by
the outer read-only transaction — see [Architecture overview](02-architecture-overview.md)).
`REQUIRES_NEW` suspends the outer transaction rather than reusing it, so it needs a *second*
connection from the same pool while the first is still held. Every redirect briefly needed 2
connections at once, so HikariCP's default pool of 10 structurally saturated at 5 truly
concurrent redirects, not 10 — exactly what 20-way concurrency exposed.

**Fix:** removed `@Transactional(readOnly = true)` from `resolve()` itself. It wasn't actually
load-bearing there — `repository.findByShortCode(...)` already gets its own short read transaction
automatically (Spring Data JPA's default for any repository query method), and
`incrementClickCount()` already had its own explicit transaction. Without the outer annotation,
each DB call acquires and releases its own connection *sequentially* instead of the method holding
one open while a second is grabbed on top of it - same correctness, half the peak connection
pressure per request.

**Validated the fix, not just applied it:** re-ran the same test - 200/200 redirects succeeded,
average latency dropped from ~6s (dominated by the 23 timeouts) to 20ms. Pushed further to 400
requests at 40 concurrent (4x the pool size) - still 0 failures. Full `mvn verify` (27 tests, PMD,
SpotBugs) still passes after the change.

## Quality gates

Beyond `mvn test`, `mvn verify` runs three more checks, and one more is available on demand:

- **PMD** (`pmd-ruleset.xml`, categories: bestpractices/errorprone/design) — catches real defects,
  not just style. First run found 11 violations; 7 were genuine and fixed (missing
  `serialVersionUID` on 4 exception classes, two places throwing a new exception without chaining
  the original cause — losing debuggability — a missing `Locale` on a case-insensitive string
  comparison, a missing `@FunctionalInterface` marker). 3 were false positives for intentional
  patterns (a JPA entity, a Spring Boot main class, a one-level getter call) — excluded centrally
  in `pmd-ruleset.xml`, not suppressed in source; see
  [Architecture overview](02-architecture-overview.md#key-decisions) for why.
- **SpotBugs** (bytecode-level, effort `Max`, threshold `Medium`) — zero findings.
- **jacoco** — coverage report, not gated on a minimum threshold (see Limitations below).
- **OWASP `dependency-check-maven`** — dependency vulnerability scan against the NVD database.
  Deliberately *not* bound to the default build lifecycle: it syncs ~385K NVD records over the
  network on first run, which the tool itself warns can take a very long time without an NVD API
  key. Run explicitly: `mvn org.owasp:dependency-check-maven:check`.

## Limitations and trade-offs

- Analytics is intentionally minimal (total clicks + last access) — no per-day/referrer/geo
  breakdown; see [Three scenarios](03-three-scenarios.md#3-ambiguous--scoping-analytics).
- No auth/rate-limiting on `POST /api/v1/urls` — anyone can mint short codes or exhaust the
  keyspace; acceptable for a prototype, not for production. Not in the assignment's stated
  requirements, and a real auth model (API keys? full accounts?) isn't specified either — building
  one would be guessing at a requirement nobody stated, the same ambiguity-scoping judgment call as
  Scenario 3. If asked for one improvement here, rate-limiting on the create endpoint would come
  before full auth — it's a smaller build and more directly a "reliability" concern.
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
- jacoco reports coverage but doesn't enforce a minimum via `jacoco:check` — a deliberate choice:
  batching all tests to hit an arbitrary percentage threshold would work against attaching each
  test to the commit that actually needs it (see `.agents/engineering-log.md`), and 92% achieved
  organically is a stronger signal than a number gamed to pass a gate.
- Load testing is a curl-based approximation (`scripts/load-test.sh`), not a real load-testing
  tool — no ramp-up profile, no latency percentiles, no distributed load generation. It's what
  found and validated the fix for the connection-pool exhaustion bug above, but a proper JMeter/k6
  setup would give a much clearer picture of behavior under sustained/production-scale load than
  a fixed-burst curl script can.
