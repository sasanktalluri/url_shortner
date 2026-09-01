# Engineering log

## Starting point

A skeleton existed already: entity/repository/DTOs, create + redirect endpoints, short-code
generation, basic validation and exception mapping. `pom.xml` was a bare Maven stub with no
dependencies at all — no Spring Boot parent, nothing — despite the source using
`@SpringBootApplication`/`@RestController`/JPA annotations throughout. No `application.yml`, no
Flyway migration, no tests, no docs.

## Requirement understanding

The assignment brief (`docs/010 - Assignment - AI-Proficient Software Engineer.pdf`) asks for a
URL shortener with "core APIs, analytics, and reliability features," built and improved with AI
assistance while demonstrating engineering judgment, and specifically wants three worked
scenarios — greenfield, brownfield, ambiguous — each showing decomposition, execution, and
validation. First step was reviewing the existing skeleton against that brief before writing any
code: it covered "core APIs" (create + redirect) but nothing for analytics, and — as it turned
out later — wasn't actually buildable as committed.

## Decomposition

The work naturally split into: (1) make the skeleton buildable and runnable at all (missing
config/migrations/deps), (2) add the missing analytics feature, (3) fix a concurrency/correctness
bug found while reading the existing collision-retry code, (4) harden error handling, (5) add
test coverage, (6) write it up. That became the basis for the "three scenarios" framing in
`README.md`: (2)+scoping-analytics as greenfield/ambiguous, (3) as brownfield.

## Infrastructure: several reversals, worth recording honestly

This went back and forth more than once, each time on the user's explicit direction:

1. Built out a full Redis caching layer (`ShortUrlLookupCache`, cache-aside on redirects) plus
   `docker-compose.yml` with both Postgres and Redis, on request ("add those" to close the gaps
   found in the initial review).

2. User then asked to drop Docker entirely and use their already-running local Postgres, **and**
   to remove Redis from the codebase rather than also run it locally — the caching layer was
   deleted, `RedirectService` reverted to reading Postgres directly, `spring-boot-starter-data-redis`
   dropped from `pom.xml`.

3. Discovered the user's local Postgres was a full EnterpriseDB install on port 5432 already
   serving other databases (`pgAdmin` open, `visitor_db` present) — didn't touch it, asked how the
   app should connect instead.

4. User then asked for Docker again ("no overhead, can run by commands") — moved to a plain
   `docker run` command rather than a committed `docker-compose.yml`, since the user had no Docker
   installed at that point.

5. Installed Docker Desktop via Homebrew (the final `sudo` symlink step needed an interactive
   password prompt the agent can't supply — the user ran that part themselves).

6. User asked for `docker-compose.yml` back, specifically for Postgres, as part of the project
   skeleton commit.

7. Credentials: first pass hardcoded `POSTGRES_USER`/`POSTGRES_PASSWORD` directly in
   `docker-compose.yml`; user asked for different, non-matching values, then asked for them to come
   from environment variables instead of being hardcoded at all. Landed on a git-ignored `.env` at
   the repo root, read by both `docker-compose.yml` (`${DB_USER}` etc.) and, when exported into the
   shell, by the Spring app itself.

Net effect: the final state (Postgres-only via `docker-compose.yml` + `.env`, no Redis) reflects
several real preference changes, not a single planning pass — recorded here rather than only in
the final README, which just describes where things ended up.

## Git history: rebuilt once, deliberately

The user wanted clean, reviewable commits for a submission that would be read by a human
reviewer — asked for options, chose a story-driven ~8-commit plan (skeleton → boot config →
analytics → bug fix → error handling → tests → integration test → docs) over coarser or more
granular alternatives.

Partway through, `.idea/` files turned out to be committed because the project's `.gitignore` only
excluded a few specific IntelliJ files, not the whole directory. Since nothing had been pushed
yet, the fix was to correct `.gitignore` and rebuild the affected commit from scratch (using
`git update-ref -d` to return to a clean root state, since there's no "parent of the first commit"
to reset to) rather than leaving stray IDE files in the history.

Later, the user pushed back on batching all tests into two commits at the end ("every commit
[should carry] coverage based on jacoco") — with one hard constraint surfaced in response: no test
can exist before a commit adds `spring-boot-starter-test`, since commit 1's `pom.xml` had zero
dependencies. The revised plan attached tests to the commit that introduced the code they cover.
That plan was then explicitly abandoned by the user in favor of finishing all remaining code and
tests first and committing in fewer, larger steps — "you can see all the code already, need all
the tests" — which is what actually shipped.

One root-commit history rewrite happened after the *first* real push (to `origin/main`), when a
bug fix (below) needed to land inside the skeleton commit rather than as a later patch, so that
commit would actually build and boot on its own. That commit was amended and the amended history
was force-pushed — the remote had nothing on it but GitHub's own auto-generated placeholder
`.gitignore`/`README.md` at that point, so nothing of value was overwritten. Confirmed via
`git ls-remote` before doing it.

## "Build and run it, not just test it"

After a full feature pass (analytics, the transaction fix, exception handling, full test suite,
README) looked complete and all unit tests passed, the user explicitly asked for the app to be
*built and run*, not just compiled and tested. That surfaced two real defects that no amount of
`mvn test` would have caught, because neither is exercised by a unit test or even the compiler:

1. **Missing `spring-boot-flyway` dependency.** Spring Boot 4 split Flyway autoconfiguration into
   its own module; having `flyway-core`/`flyway-database-postgresql` on the classpath alone wasn't
   enough. The app booted, Hibernate tried to validate against a schema Flyway had never touched,
   and failed with "missing table [short_urls]." Found by actually starting the app and reading the
   startup log, not by any test.

2. **`RedirectService`/`UrlService` wouldn't boot.** Both had a public constructor plus a
   package-private one used only by tests, and neither was marked `@Autowired`. Spring Framework 7
   doesn't auto-pick a constructor when there's more than one candidate; it fell back to a no-arg
   constructor that didn't exist and threw `NoSuchMethodException`. This bug was **already present
   in the original skeleton**, sitting in a commit that had already been pushed — fixed by amending
   that commit and force-pushing again, rather than leaving a known-broken commit in the history.

A related environment issue, not a code bug: the pinned Testcontainers version (`1.21.3`) couldn't
negotiate with this machine's Docker Desktop version — `docker info`/`docker ps` worked fine from
the shell, but the Java client got a malformed stub response and failed with "Could not find a
valid Docker environment." Bumped to `1.21.4`, which resolved it — recorded in the README as a
concrete found-and-fixed issue, not glossed over.

Given how much this surfaced, the same discipline was repeated on every later feature pass: after
building the strategy-pattern short-code generation, the app was run again end-to-end and 5
consecutive codes were checked by hand to confirm they didn't look sequential.

## Short-code generation: strategy pattern, and a design argument that changed the shape of it

The user asked for the random generator to become one implementation of a strategy pattern, with
a second, "optimal" implementation using an atomic counter — encoding a Postgres sequence value
rather than emitting it raw, since a raw sequential ID is enumerable (anyone could walk `/100001`,
`/100002`, ... and discover every link ever created).

First proposal from the agent was to encode the counter via a hand-rolled bit-mixing/Feistel-style
permutation for obfuscation, plus an in-memory block/range allocator for throughput. Recommendation
given at the time: build the strategy pattern and the plain counter+encode now, but *not* the
mixing or block allocation in the same pass — a hand-rolled bijection is easy to get subtly wrong
(a non-bijective mixing function silently reintroduces collisions, and that class of bug wouldn't
surface until the keyspace was large enough to hit it), and block allocation adds concurrent,
stateful logic that needs its own dedicated tests to trust. Both deferred, documented as limitations.

The user's follow-up resolved the main objection directly: use the `sqids.org` library (a proven,
maintained reversible-mapping implementation) instead of hand-rolling the mixing function. That
removed the "might be a subtly wrong bijection" risk entirely, so it was built as `SqidsShortCodeGenerator`
— Postgres sequence value in, Sqids-encoded string out, `org.sqids:sqids` resolved from Maven
Central. Block allocation is still deferred (documented in `README.md`'s limitations) since the
plain sequence isn't actually a bottleneck yet.

First cut wired `SqidsShortCodeGenerator` in via `@Primary`, with the collision-retry loop still
living in `UrlService`, unchanged from before. The user rejected that on two points, both acted on:

- **The retry loop shouldn't be `UrlService`'s job.** Initial fix moved it into a new
  `ShortCodeAllocator` component. The user rejected *that too* — "why can't you wrap that function
  directly in random short code generator instead of creating a new allocator, it's not cool" —
  so the interface changed shape: `ShortCodeGenerator.createShortUrl(...)` now does
  generate-candidate + persist + retry as one unit, and each implementation owns its own loop
  directly. This surfaced a real correctness point worth keeping: even the Sqids strategy still
  needs a retry path, not for self-collisions (structurally impossible — a sequence value never
  repeats) but because a generated code can still clash with a pre-existing *custom alias*, since
  aliases and generated codes share one uniqueness namespace. Retrying is safe there too, since
  each candidate consumes a new sequence value.
- **`@Primary` replaced with `@Qualifier`.** The user asked for explicit `@Qualifier("sqids")` /
  `@Qualifier("random")` naming instead, with `UrlService`'s constructor declaring which strategy
  it wants — makes the "which strategy is the default" decision visible at the injection point
  rather than an implicit fact about which bean happens to be marked primary. Asked for this to be
  tested directly, not just inferred from the app working — added `ShortCodeGeneratorWiringTest`,
  a real (but DB-mocked) Spring context test that would fail on a wrong qualifier name, a missing
  bean, or an ambiguous injection point, independent of whether each generator class works in
  isolation.

While rebuilding `SqidsShortCodeGenerator` with its own constructor-injected collaborators, it
picked up the exact same two-constructor/no-`@Autowired` shape that had caused the earlier boot
failure. Caught and fixed before it shipped, by deliberately checking for that specific pattern
this time rather than relying on it surfacing again at boot.

## Validation performed

- Full unit suite run after every meaningful change (27 tests as of the last pass), plus jacoco
  coverage (92% overall line coverage at last measurement).
- `UrlShortenerIntegrationTest` — real Postgres via Testcontainers, all Flyway migrations applied
  for real, exercised through actual HTTP calls: create → redirect → stats, plus a 404 case.
- The running app itself exercised by hand with `curl` multiple times across the session: create,
  redirect (302 + correct `Location`), stats, unknown-code 404, invalid-scheme 422, three
  concurrent-ish redirects landing exactly `clickCount: 3` (verifying the atomic-increment fix
  actually avoids lost updates), and — after the strategy-pattern rework — five consecutive
  sequence values producing five codes with no visible pattern.
- `scripts/run.sh` itself was run from a cold `docker compose down` to confirm the automated setup
  path actually works, not just the manual steps in the README.

## Assumptions and limitations (see `README.md` for the full list)

Recorded here as things the agent chose, not things the assignment specified: local development
is the only target (no CI config, no secrets manager beyond a git-ignored `.env`), the reviewer is
assumed to have Docker available (both running the app and the integration test depend on it), and
several capabilities were deliberately deferred rather than built — per-day/referrer analytics,
auth/rate-limiting, and the block-allocation optimization for short-code generation — each with the
reasoning for deferring it written down in `README.md` rather than silently left out.

## Comparing against another candidate's implementation

The user shared a different engineer's implementation of the same assignment. Pulled it in full
(README, file tree, `pom.xml`, `docs/TESTING.md`), and gave an honest comparison rather than
copying it wholesale.

Genuine gaps worth considering: SSRF/loopback URL validation, a deactivate endpoint. Differences
that aren't deficiencies: their async click-tracking can silently lose clicks on failure (their
own test admits it), unlike this project's synchronous atomic update; their test suite has zero
tests touching a real database, unlike this project's Testcontainers integration test.

## Deactivate: built. Auth: built, then reverted

Deactivate (`DELETE /api/v1/urls/{shortCode}`) was built and kept - unauthenticated, like every
other endpoint, which is a real open gap: anyone who knows a code can deactivate it.

SSRF validation was declined ("this is not required") in favor of auth instead. HTTP Basic auth
was then built for real - a `SecurityConfig`, credentials via `.env`, everything gated except
`GET /{shortCode}` - and verified working end to end, before being explicitly reverted at the
user's direction. Rate limiting (`bucket4j`) was scoped alongside it but never built.
