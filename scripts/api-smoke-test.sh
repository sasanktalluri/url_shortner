#!/usr/bin/env bash
# Functional smoke test: exercises every API combination against a running instance -
# create with/without a custom alias, a colliding alias, invalid input, expiry, redirect
# success/404/410, and stats success/404. Prints PASS/FAIL per case and exits non-zero if
# anything failed.
#
# Usage: BASE_URL=http://localhost:8080 ./scripts/api-smoke-test.sh
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
FAILURES=0

json_field() {
  python3 -c "import json,sys; print(json.load(sys.stdin).get('$1',''))" 2>/dev/null
}

check() {
  local description="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    echo "PASS  $description (got $actual)"
  else
    echo "FAIL  $description (expected $expected, got $actual)"
    FAILURES=$((FAILURES + 1))
  fi
}

echo "== Create: no alias =="
resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/no-alias"}')
code=$(echo "$resp" | tail -1)
body=$(echo "$resp" | sed '$d')
check "create without alias -> 201" 201 "$code"
GENERATED_CODE=$(echo "$body" | json_field shortCode)

echo "== Create: fresh custom alias =="
ALIAS="smoke-test-alias-$RANDOM"
resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE_URL/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d "{\"url\":\"https://example.com/with-alias\",\"customAlias\":\"$ALIAS\"}")
code=$(echo "$resp" | tail -1)
check "create with fresh custom alias -> 201" 201 "$code"

echo "== Create: same alias again (collision) =="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d "{\"url\":\"https://example.com/dup\",\"customAlias\":\"$ALIAS\"}")
check "create with already-existing alias -> 409" 409 "$code"

echo "== Create: disallowed URL scheme =="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d '{"url":"ftp://example.com/bad"}')
check "create with ftp:// scheme -> 422" 422 "$code"

echo "== Create: malformed request body (missing url) =="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d '{}')
check "create with missing url -> 400" 400 "$code"

echo "== Create: expiresAt already in the past =="
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/past","expiresAt":"2000-01-01T00:00:00Z"}')
check "create with past expiresAt -> 400" 400 "$code"

echo "== Create: short-lived URL, then let it expire =="
soon=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
resp=$(curl -s -X POST "$BASE_URL/api/v1/urls" \
  -H 'Content-Type: application/json' \
  -d "{\"url\":\"https://example.com/expiring\",\"expiresAt\":\"$soon\"}")
EXPIRING_CODE=$(echo "$resp" | json_field shortCode)
sleep 3

echo "== Redirect: valid code =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/$GENERATED_CODE")
check "redirect valid code -> 302" 302 "$code"

echo "== Redirect: unknown code =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/does-not-exist-$RANDOM")
check "redirect unknown code -> 404" 404 "$code"

echo "== Redirect: expired code =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/$EXPIRING_CODE")
check "redirect expired code -> 410" 410 "$code"

echo "== Stats: valid code, click count reflects the redirect above =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/urls/$GENERATED_CODE/stats")
check "stats valid code -> 200" 200 "$code"
count=$(curl -s "$BASE_URL/api/v1/urls/$GENERATED_CODE/stats" | json_field clickCount)
if [ "${count:-0}" -ge 1 ] 2>/dev/null; then
  echo "PASS  clickCount incremented by the redirect (got $count)"
else
  echo "FAIL  clickCount not incremented (got ${count:-<none>})"
  FAILURES=$((FAILURES + 1))
fi

echo "== Stats: unknown code =="
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/api/v1/urls/does-not-exist-$RANDOM/stats")
check "stats unknown code -> 404" 404 "$code"

echo
if [ "$FAILURES" -eq 0 ]; then
  echo "All checks passed."
  exit 0
else
  echo "$FAILURES check(s) failed."
  exit 1
fi
