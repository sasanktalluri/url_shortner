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
- No load/performance testing — beyond the theoretical collision-math in
  [Architecture overview](02-architecture-overview.md) and one manual concurrency check (3
  redirects → `clickCount: 3`), there's no load test. Reasonable to defer at prototype scale, and
  named here rather than left unmentioned.
