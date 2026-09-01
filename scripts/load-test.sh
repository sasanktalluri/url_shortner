#!/usr/bin/env bash
# Lightweight curl-based load test: fires TOTAL concurrent-ish POST /api/v1/urls requests
# (CONCURRENCY at a time), then does the same against the redirect endpoint for every code just
# created, and reports success rate + latency.
#
# This is NOT a substitute for a real load-testing tool (JMeter, Gatling, k6): those give
# configurable ramp-up profiles, latency percentiles (p50/p95/p99), and distributed load
# generation that a parallel curl loop cannot. This script is a deliberately lightweight stand-in
# appropriate for this project's scope - see docs/05-testing-approach.md for that trade-off
# written down properly, and for what a real load-testing setup would add.
#
# Usage: ./scripts/load-test.sh [CONCURRENCY] [TOTAL]
#   BASE_URL=http://localhost:8080 ./scripts/load-test.sh 20 200
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENCY="${1:-20}"
TOTAL="${2:-200}"

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

echo "Load test: $TOTAL create requests, $CONCURRENCY at a time, against $BASE_URL"
echo

create_one() {
  local i="$1"
  local result
  result=$(curl -s -o "$WORKDIR/body-$i.json" -w '%{http_code} %{time_total}' \
    -X POST "$BASE_URL/api/v1/urls" \
    -H 'Content-Type: application/json' \
    -d "{\"url\":\"https://example.com/load-test-$i\"}")
  echo "$i $result" >> "$WORKDIR/create-results.txt"
}
export -f create_one
export WORKDIR BASE_URL

seq 1 "$TOTAL" | xargs -P "$CONCURRENCY" -I{} bash -c 'create_one "$@"' _ {}

echo "== Create results =="
total=$(wc -l < "$WORKDIR/create-results.txt" | tr -d ' ')
success=$(awk '$2==201' "$WORKDIR/create-results.txt" | wc -l | tr -d ' ')
avg_ms=$(awk '{sum+=$3; n++} END {if (n>0) printf "%.0f", (sum/n)*1000}' "$WORKDIR/create-results.txt")
max_ms=$(awk 'BEGIN{m=0} {if($3>m)m=$3} END{printf "%.0f", m*1000}' "$WORKDIR/create-results.txt")
echo "Total: $total   Success (201): $success   Failures: $((total - success))"
echo "Avg latency: ${avg_ms}ms   Max latency: ${max_ms}ms"
if [ "$success" -lt "$total" ]; then
  echo "Non-201 responses:"
  awk '$2!=201' "$WORKDIR/create-results.txt"
fi

for f in "$WORKDIR"/body-*.json; do
  python3 -c "import json,sys; d=json.load(open('$f')); print(d.get('shortCode',''))" 2>/dev/null
done | grep -v '^$' > "$WORKDIR/codes.txt"

echo
echo "== Redirect load (same $(wc -l < "$WORKDIR/codes.txt" | tr -d ' ') codes, $CONCURRENCY concurrent) =="

redirect_one() {
  local code="$1"
  local result
  result=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' "$BASE_URL/$code")
  echo "$code $result" >> "$WORKDIR/redirect-results.txt"
}
export -f redirect_one
export WORKDIR BASE_URL

xargs -P "$CONCURRENCY" -I{} bash -c 'redirect_one "$@"' _ {} < "$WORKDIR/codes.txt"

rtotal=$(wc -l < "$WORKDIR/redirect-results.txt" | tr -d ' ')
rsuccess=$(awk '$2==302' "$WORKDIR/redirect-results.txt" | wc -l | tr -d ' ')
ravg_ms=$(awk '{sum+=$3; n++} END {if (n>0) printf "%.0f", (sum/n)*1000}' "$WORKDIR/redirect-results.txt")
echo "Total: $rtotal   Success (302): $rsuccess   Failures: $((rtotal - rsuccess))"
echo "Avg latency: ${ravg_ms}ms"
if [ "$rsuccess" -lt "$rtotal" ]; then
  echo "Non-302 responses:"
  awk '$2!=302' "$WORKDIR/redirect-results.txt"
fi

echo
if [ "$success" -eq "$total" ] && [ "$rsuccess" -eq "$rtotal" ]; then
  echo "Load test passed: all requests succeeded."
  exit 0
else
  echo "Load test found failures - see above."
  exit 1
fi
