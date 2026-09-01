# Deliverable: Setup Instructions

**Prerequisites:** Java 21, Maven, Docker.

## Fastest path

```bash
./scripts/run.sh
```
Starts Postgres via `docker compose`, waits for it to be healthy, then runs the app (Flyway
migrations apply automatically on startup).

## By hand

```bash
docker compose up -d       # start Postgres
mvn spring-boot:run        # run the app
```

## Try it

```bash
curl -X POST localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"url": "https://example.com/some/long/path"}'
# => {"shortCode":"UdBdLWZ","shortUrl":"http://localhost:8080/UdBdLWZ", ...}

curl -i localhost:8080/UdBdLWZ                       # 302 redirect
curl localhost:8080/api/v1/urls/UdBdLWZ/stats        # click count / last accessed
```

Interactive API docs (Swagger UI, generated from the controllers — see
[Architecture overview](02-architecture-overview.md)) are at `http://localhost:8080/swagger-ui/index.html`
once the app is running; the raw OpenAPI 3.1 document is at `http://localhost:8080/v3/api-docs`.

## Configuration

`.env` (git-ignored, at the repo root) needs:
```
DB_NAME=urlshortener
DB_USER=app_user
DB_PASSWORD=app_pw_local_dev
DB_PORT=5432
```

If port 5432 is already taken by another local Postgres, change `DB_PORT` there
(`docker-compose.yml` reads it for both the container's published port and the healthcheck) and
pass the same value to the app, e.g. `DB_PORT=5433 mvn spring-boot:run` (`scripts/run.sh` picks up
whatever's in `.env` automatically either way).

Full list of env vars the app reads (see `src/main/resources/application.yml`): `DB_HOST`,
`DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `APP_BASE_URL`, `SERVER_PORT`.

## Testing scripts (run against the live app)

```bash
./scripts/api-smoke-test.sh              # every API combination: alias/no alias/duplicate
                                          # alias/invalid input/expiry/redirect/stats - 12 checks
BASE_URL=http://localhost:8080 \
  ./scripts/load-test.sh [CONCURRENCY] [TOTAL]   # curl-based load test, defaults 20/200
```
See [Testing approach](05-testing-approach.md#load-testing) for what each covers and a real bug
`load-test.sh` found (connection-pool exhaustion under concurrent redirects).

## Troubleshooting

Every entry here is a real problem hit and fixed while building this project, not a hypothetical.

**"Port 5432 was already in use"**
Another local Postgres is already listening on the default port. Set a different `DB_PORT` in
`.env` (`docker-compose.yml` reads it for the container's published port too) and re-run
`./scripts/run.sh`, or pass it inline: `DB_PORT=5433 mvn spring-boot:run`.

**"Web server failed to start. Port 8080 was already in use"**
An earlier `mvn spring-boot:run` is still running from a previous session. Find and stop it:
```bash
pkill -f "spring-boot:run"
# or, if that doesn't catch it:
lsof -iTCP:8080 -sTCP:LISTEN -n -P
kill <PID from the output above>
```

**Docker Desktop is running, but Testcontainers (the integration test, or `load-test.sh`) can't
find it — `Could not find a valid Docker environment`, even though `docker info`/`docker ps` work
fine from the shell**
Docker Desktop's active context isn't always at the socket path Testcontainers checks by default.
Point it explicitly at the real one:
```bash
docker context ls                       # find the "DOCKER ENDPOINT" for your active (*) context
export DOCKER_HOST="unix:///path/from/above/docker.sock"
mvn test
```

**Testcontainers itself fails with a malformed/empty response from Docker, even with `DOCKER_HOST`
set correctly**
A pinned Testcontainers version can be incompatible with a newer Docker Desktop release. This
project already pins `1.21.4` in `pom.xml` for exactly this reason (`1.21.3` didn't work here) —
if it recurs on a different machine, try bumping `testcontainers.version` further.

**`mvn spring-boot:run` seems stuck / a terminal won't return control**
`Ctrl+C` to cancel it. If that doesn't work, `pkill -f "spring-boot:run"` from another terminal.

## Quality gates (optional, for reviewing the codebase itself)

```bash
mvn verify                                    # tests + jacoco + PMD + SpotBugs, all must pass
mvn org.owasp:dependency-check-maven:check    # dependency vulnerability scan (not bound to
                                               # verify - syncs the NVD database over the network
                                               # first, which is slow and can take a long time
                                               # without an NVD API key)
```
See [Testing approach](05-testing-approach.md) for what each of these actually checks.
