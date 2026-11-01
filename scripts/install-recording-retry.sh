#!/usr/bin/env bash
set -euo pipefail

# Keep the scheduled maintenance JAR aligned with the healthy deployed app image.
cd "$(dirname "$0")/.."
maintenance_dir=/home/ubuntu/rparadise-maintenance
install -d -m 700 "$maintenance_dir"
if sudo systemctl cat rparadise-recording-retry.service >/dev/null 2>&1; then
  sudo systemctl stop rparadise-recording-retry.service
fi
exec 9>"$maintenance_dir/recording-backfill.jar.lock"
flock -w 60 9
app_container=$(sudo docker compose -f compose.yaml -f compose.observability.yaml ps -q app)
if [ -z "$app_container" ]; then
  echo 'A healthy deployed app is required before installing the retry worker.' >&2
  exit 1
fi
health=$(sudo docker inspect --format '{{.State.Health.Status}}' "$app_container")
if [ "$health" != healthy ]; then
  echo 'The deployed app is not healthy; keeping the previous maintenance JAR.' >&2
  exit 1
fi
sudo docker cp "$app_container:/app/app.jar" "$maintenance_dir/recording-backfill.jar.next"
sudo chown ubuntu:ubuntu "$maintenance_dir/recording-backfill.jar.next"
chmod 644 "$maintenance_dir/recording-backfill.jar.next"
mv "$maintenance_dir/recording-backfill.jar.next" "$maintenance_dir/recording-backfill.jar"
sudo install -m 644 scripts/systemd/rparadise-recording-retry.service /etc/systemd/system/
sudo install -m 644 scripts/systemd/rparadise-recording-retry.timer /etc/systemd/system/
sudo systemd-analyze verify /etc/systemd/system/rparadise-recording-retry.service /etc/systemd/system/rparadise-recording-retry.timer
sudo systemctl daemon-reload
sudo systemctl enable --now rparadise-recording-retry.timer
sudo systemctl list-timers rparadise-recording-retry.timer --no-pager
