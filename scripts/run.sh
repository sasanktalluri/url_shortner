#!/usr/bin/env bash
# Starts Postgres via docker compose, waits for it to be healthy, then runs the app.
# Equivalent to the manual steps in README.md's Setup section.
set -euo pipefail

cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "Missing .env in the project root - see README.md Setup for the required variables." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is not reachable. Start Docker Desktop (or your Docker runtime) and re-run this script." >&2
  exit 1
fi

echo "Starting Postgres (docker compose up -d)..."
docker compose up -d

echo "Waiting for Postgres to report healthy..."
container_id="$(docker compose ps -q postgres)"
until [ "$(docker inspect --format='{{.State.Health.Status}}' "$container_id" 2>/dev/null)" = "healthy" ]; do
  sleep 1
done
echo "Postgres is healthy."

echo "Starting the application (Flyway migrations apply automatically)..."
set -a
# shellcheck disable=SC1091
source .env
set +a
exec mvn spring-boot:run
