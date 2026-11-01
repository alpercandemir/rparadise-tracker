#!/usr/bin/env bash
set -euo pipefail

# Run on the deployment host. The maintenance JAR can be newer than the live app.
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "Usage: $0 /absolute/path/to/backfill.jar [missing|errors]" >&2
  exit 2
fi
jar_path="$1"
mode="${2:-missing}"
case "$jar_path" in
  /*) ;;
  *) echo 'The maintenance JAR path must be absolute.' >&2; exit 2 ;;
esac
if [ ! -f "$jar_path" ]; then
  echo 'Maintenance JAR not found.' >&2
  exit 2
fi
case "$mode" in
  missing) retry_errors=false ;;
  errors) retry_errors=true ;;
  *) echo 'Mode must be missing or errors.' >&2; exit 2 ;;
esac

cd "$(dirname "$0")/.."
exec 9>"${jar_path}.lock"
if ! flock -n 9; then
  echo 'A recording backfill is already running; skipping this invocation.'
  exit 0
fi
app_container=$(sudo docker compose -f compose.yaml -f compose.observability.yaml ps -q app)
if [ -z "$app_container" ]; then
  echo 'The application container must be running to select its runtime image.' >&2
  exit 1
fi
APP_IMAGE=$(sudo docker inspect --format '{{.Config.Image}}' "$app_container")
export APP_IMAGE
# --rm overrides the service restart policy. A fixed name prevents overlapping workers.
stop_worker() {
  sudo docker stop --time 30 rparadise-recording-backfill >/dev/null 2>&1 || true
}
trap stop_worker INT TERM
sudo --preserve-env=APP_IMAGE docker compose -f compose.yaml -f compose.observability.yaml \
  -f compose.production.yaml -f compose.recording-backfill.yaml \
  run --rm --no-deps --interactive=false --name rparadise-recording-backfill \
  --entrypoint java --volume "$jar_path:/app/recording-backfill.jar:ro" app \
  -jar /app/recording-backfill.jar --spring.profiles.active=recording-backfill \
  --recording.backfill.retry-errors-only="$retry_errors" &
worker_pid=$!
wait "$worker_pid"
