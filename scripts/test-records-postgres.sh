#!/usr/bin/env bash
set -euo pipefail

# Disposable local database only. Existing local/production databases are never used.
cd "$(dirname "$0")/.."
container=$(docker run --rm -d -e POSTGRES_DB=records_test -e POSTGRES_USER=records_test \
  -e POSTGRES_PASSWORD=records_test -p 127.0.0.1::5432 postgres:17)
trap 'docker rm -f "$container" >/dev/null' EXIT
ready=false
for attempt in {1..30}; do
  if docker exec "$container" pg_isready -U records_test -d records_test >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  echo 'Disposable PostgreSQL did not become ready' >&2
  exit 1
fi
port=$(docker port "$container" 5432/tcp | cut -d: -f2)
RECORDS_DB_TESTS=true DB_URL="jdbc:postgresql://127.0.0.1:$port/records_test" \
  DB_USERNAME=records_test DB_PASSWORD=records_test TZ=UTC ./mvnw -B -ntp verify
