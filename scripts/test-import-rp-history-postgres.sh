#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
container=$(docker run --rm -d -e POSTGRES_DB=history_import_test \
  -e POSTGRES_USER=history_import_test -e POSTGRES_PASSWORD=history_import_test postgres:17)
trap 'docker rm -f "$container" >/dev/null' EXIT
ready=false
for attempt in {1..30}; do
  if docker exec "$container" pg_isready -U history_import_test -d history_import_test >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done
if [ "$ready" != true ]; then
  echo 'Disposable PostgreSQL did not become ready' >&2
  exit 1
fi
HISTORY_DB_TESTS=true HISTORY_TEST_CONTAINER="$container" \
  python3 -m unittest discover -s scripts -p 'test_import_rp_history.py' -v
HISTORY_DB_TESTS=true HISTORY_TEST_CONTAINER="$container" \
  python3 -m unittest discover -s scripts -p 'test_merge_rp_history.py' -v
