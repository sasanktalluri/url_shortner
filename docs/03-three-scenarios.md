# Deliverable: Three Scenarios (Greenfield, Brownfield, Ambiguous)

## 1) Greenfield — click analytics

The original prototype had create + redirect but no analytics, despite the brief asking for "core
APIs, analytics, and reliability features."

**Decomposition:** add `click_count`/`last_accessed_at` columns (`V2` migration) → atomic
increment query on the repository → wire the increment into `RedirectService.resolve` → add
`GET /api/v1/urls/{shortCode}/stats`.

**Execution:** implemented as a single atomic `UPDATE ... SET click_count = click_count + 1`, not
a read-modify-write on the loaded entity — avoids lost updates under concurrent redirects to the
same code.

**Validation:** unit tests, the Testcontainers integration test (create → redirect → stats), and a
manual run confirming 3 redirects to the same code landed exactly `clickCount: 3`.

## 2) Brownfield — fixing the collision-retry bug

The original `UrlService.create()` retried short-code generation up to 5 times on a
unique-constraint collision, catching `DataIntegrityViolationException` inside what was originally
one `@Transactional` method.

**Codebase reasoning:** on Postgres, a failed statement aborts the *entire* transaction — every
retry after the first collision would fail with `25P02: current transaction is aborted`, not
actually retry. Root cause: the transactional boundary was at the wrong level (the whole
`create()` call) instead of per-attempt.

**Execution:** extracted the write into `ShortUrlWriter.save()`, its own Spring bean with
`@Transactional(propagation = REQUIRES_NEW)`, so each generation attempt runs in an independent
transaction that a prior attempt's failure can't poison.

**Validation:** a regression test forces a first-attempt collision and asserts the second attempt
succeeds (`RandomShortCodeGeneratorTest.retriesWithANewCandidateOnCollision`).

## 3) Ambiguous — scoping "analytics"

The brief says "analytics" with no further spec: per-day breakdowns? referrer/geo tracking?
real-time dashboards?

**Interpretation:** given the assignment's timebox and no stakeholder to ask, the interpretation
taken was the smallest analytics surface that's still genuinely useful — total click count +
last-accessed timestamp per short URL, exposed via a stats endpoint.

**Rejected alternatives and why:**
- A per-click event log — unbounded table growth, no consumer for the data yet.
- A background/async click pipeline — adds a queue/worker for a feature that's currently
  low-volume and needs strong consistency with create/redirect, not eventual consistency.

**Validation:** same as Scenario 1 (this *is* the analytics feature); the scoping decision itself
is validated by it being documented as a known limitation rather than silently under-delivered —
see [Testing approach](05-testing-approach.md#limitations-and-trade-offs).

## Two more scenarios that emerged from actually running the app

Not originally planned as "scenarios," but worth recording the same way — both were only found by
building and running the app end-to-end, not by compiling or unit testing:

- **Spring Boot 4 moved Flyway autoconfiguration into its own module** (`spring-boot-flyway`) -
  having `flyway-core` and `flyway-database-postgresql` on the classpath was not sufficient; the
  app booted but Hibernate tried to validate against a schema Flyway had never touched, and failed
  with "missing table [short_urls]". Fixed by adding `spring-boot-flyway` as an explicit
  dependency.
- **`RedirectService`/`UrlService` failed to boot** — both have a public constructor plus a
  package-private one used only by tests, and neither was marked `@Autowired`. Spring Framework 7
  won't pick a constructor automatically when there's more than one candidate; it fell back to
  looking for a no-arg constructor, found none, and threw `NoSuchMethodException`. Fixed by adding
  `@Autowired` to the intended constructor in both classes — and the same pattern was caught again
  in `SqidsShortCodeGenerator` before it shipped, by deliberately checking for it this time.

See [Working prototype](01-working-prototype.md) for the full validation trail these came from.
