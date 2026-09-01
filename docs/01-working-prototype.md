# Deliverable: Working Prototype (Runnable End-to-End)

Setup instructions are in their own doc: [Setup instructions](04-setup-instructions.md).
This doc is the evidence that "runnable end-to-end" is actually true, not just claimed.

## What "runnable end-to-end" means here

Postgres up (via `docker compose` or `scripts/run.sh`) → Flyway applies all three migrations on
boot → the app serves create/redirect/stats over HTTP → the OpenAPI spec and Swagger UI are
reachable. All of that was exercised with real `curl` calls against a real running instance,
repeatedly, across the life of this project — not inferred from tests passing.

## What has been manually verified, and when

- Create → 201 with a generated short code; redirect → 302 with the correct `Location` header;
  stats → click count and last-accessed timestamp.
- Unknown short code → 404; invalid URL scheme → 422; expired URL → 410 (via `GlobalExceptionHandler`).
- Three redirects to the same code landed exactly `clickCount: 3` — confirms the atomic-increment
  fix actually prevents lost updates under concurrent-ish access, not just in theory.
- After the strategy-pattern rework: five consecutive Postgres sequence values produced five
  short codes with no visible sequential pattern (`ArUOs1i`, `l8C5iGA`, `yU79r3E`, `zHx6cwP`,
  `cVoHuas`) — confirms `SqidsShortCodeGenerator` is actually wired in and actually encoding, not
  just compiling.
- After adding `springdoc-openapi`: `/v3/api-docs` and `/swagger-ui/index.html` both return `200`
  and the generated spec's response codes match what the controllers actually return (this needed
  a fix — see [Architecture overview](02-architecture-overview.md)).
- `scripts/run.sh` run from a cold `docker compose down` — confirmed the one-command setup path
  works, not just the manual multi-step version.
- `mvn verify` (tests + jacoco + PMD + SpotBugs) passes clean end-to-end — see
  [Testing approach](05-testing-approach.md) for what each check actually covers.
- `mvn org.owasp:dependency-check-maven:check` is configured and was launched against this
  project's real dependency tree to confirm it actually runs (not just that the plugin is declared
  in `pom.xml`) — it syncs the NVD vulnerability database first, which the tool itself warns can
  take a long time without an API key; treat this as "wired up and executable," not "already run
  to completion and clean," unless you've run it yourself and let it finish.
