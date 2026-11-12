#!/usr/bin/env bash
set -euo pipefail

# Run on the Oracle host after the reviewed image is healthy. The SQL is the
# same atomic merge used locally; stop every application writer before taking locks.
if [ "$#" -ne 2 ] || [[ ! "$2" =~ ^[0-9a-f]{40}$ ]]; then
  echo 'Usage: run-production-history-merge.sh /absolute/run-directory deployed-commit-sha' >&2
  exit 2
fi
run_dir=$1
expected_sha=$2
case "$run_dir" in
  /home/ubuntu/rparadise-history-import-20261112/real-table-*) ;;
  *) echo 'Use a dedicated real-table rollout directory under the history maintenance directory.' >&2; exit 2 ;;
esac
cd "$(dirname "$0")/.."
install -d -m 700 "$run_dir"
exec 9>/home/ubuntu/rparadise-history-import-20261112/real-table-merge.lock
flock -n 9
if [ -e "$run_dir/before-public.dump" ]; then
  echo 'Run directory already has a snapshot; use a new run directory.' >&2
  exit 1
fi
app=rparadise-tracker-app-1
postgres=rparadise-tracker-postgres-1
actual_image=$(sudo docker inspect --format '{{.Config.Image}}' "$app")
if [ "$actual_image" != "ghcr.io/alpercandemir/rparadise-tracker:sha-$expected_sha" ]; then
  echo 'The expected reviewed application image must be deployed first.' >&2
  exit 1
fi
if [ "$(sudo docker inspect --format '{{.State.Health.Status}}' "$app")" != healthy ]; then
  echo 'Production application is not healthy; refusing to pause it.' >&2
  exit 1
fi
if [ "$(sudo docker exec "$postgres" psql -X -At -U radioparadise -d radioparadise \
  -c 'SELECT count(*) FROM rp_history.source_play;')" != 1298079 ]; then
  echo 'The staged production archive differs from the reviewed snapshot.' >&2
  exit 1
fi

phase() {
  printf '%s %s\n' "$(date -u +%FT%TZ)" "$1"
  printf '%s\n' "$1" > "$run_dir/phase"
}
app_stopped=0
timer_paused=0
memory_changed=0
original_limits=$(sudo docker inspect --format '{{.HostConfig.Memory}} {{.HostConfig.MemorySwap}}' "$postgres")
read -r original_memory original_swap <<< "$original_limits"
resume() {
  result=$?
  trap - EXIT
  if [ "$memory_changed" -eq 1 ]; then
    phase restoring-database-memory-limit
    if ! sudo docker update --memory="$original_memory" --memory-swap="$original_swap" "$postgres"; then
      result=1
    fi
  fi
  if [ "$app_stopped" -eq 1 ]; then
    phase restarting-application
    if ! sudo docker start "$app"; then
      result=1
    fi
    healthy=false
    for attempt in {1..150}; do
      if [ "$(sudo docker inspect --format '{{.State.Health.Status}}' "$app" 2>/dev/null)" = healthy ]; then
        healthy=true
        break
      fi
      sleep 2
    done
    if [ "$healthy" != true ]; then
      echo 'Application failed to become healthy after maintenance.' >&2
      result=1
    fi
  fi
  if [ "$timer_paused" -eq 1 ]; then
    if ! sudo systemctl start rparadise-recording-retry.timer; then
      result=1
    fi
  fi
  printf '%s\n' "$result" > "$run_dir/exit-code"
  if [ "$result" -eq 0 ]; then phase complete; else phase failed; fi
  exit "$result"
}
trap resume EXIT

phase pausing-writers
if sudo systemctl is-active --quiet rparadise-recording-retry.timer; then
  timer_paused=1
  sudo systemctl stop rparadise-recording-retry.timer
fi
sudo systemctl stop rparadise-recording-retry.service
if [ "$(sudo docker inspect --format '{{.State.Running}}' rparadise-recording-backfill 2>/dev/null || true)" = true ]; then
  sudo docker stop --time 30 rparadise-recording-backfill
fi
app_stopped=1
sudo docker stop --time 30 "$app"

# The million-row merge exceeded 768 MiB in the constrained rehearsal. The app
# is stopped first to leave host headroom; restore the original limits on exit.
phase increasing-maintenance-memory
memory_changed=1
sudo docker update --memory=2g --memory-swap=2g "$postgres"

phase backing-up-application-tables
sudo docker exec "$postgres" pg_dump -U radioparadise -d radioparadise -Fc \
  --schema=public --no-owner --no-acl > "$run_dir/before-public.dump.partial"
mv "$run_dir/before-public.dump.partial" "$run_dir/before-public.dump"
chmod 600 "$run_dir/before-public.dump"
sha256sum "$run_dir/before-public.dump" > "$run_dir/before-public.dump.sha256"
sudo docker exec "$postgres" psql -X -At -v ON_ERROR_STOP=1 -U radioparadise -d radioparadise -c \
  "SELECT json_build_object('songs',(SELECT count(*) FROM song),'observations',(SELECT count(*) FROM playback_event),'image','$actual_image');" \
  > "$run_dir/before.json"

phase merging-historical-playbacks
sudo docker exec -i "$postgres" psql -X -qAt -v ON_ERROR_STOP=1 -U radioparadise -d radioparadise \
  < scripts/merge-rp-history.sql > "$run_dir/merge.json.partial"
mv "$run_dir/merge.json.partial" "$run_dir/merge.json"

phase checking-repeat-import
sudo docker exec -i "$postgres" psql -X -qAt -v ON_ERROR_STOP=1 -U radioparadise -d radioparadise \
  < scripts/merge-rp-history.sql > "$run_dir/repeat.json.partial"
mv "$run_dir/repeat.json.partial" "$run_dir/repeat.json"

phase checking-statistics
sudo docker exec "$postgres" psql -X -qAt -v ON_ERROR_STOP=1 -U radioparadise -d radioparadise \
  -c 'ANALYZE public.song; ANALYZE public.playback_event;'
sudo docker exec -i "$postgres" psql -X -qAt -v ON_ERROR_STOP=1 -U radioparadise -d radioparadise \
  < scripts/audit-rp-history-real-tables.sql > "$run_dir/audit.json"
python3 - "$run_dir" <<'PY'
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
before, merged, repeated, audit = [json.loads((root / name).read_text())
                                  for name in ('before.json', 'merge.json', 'repeat.json', 'audit.json')]
assert audit['statistics_mismatches'] == audit['invalid_indexes'] == 0
assert audit['observations'] == before['observations']
assert audit['plays'] == audit['song_play_count_sum'] == merged['playback_events']
assert audit['imported'] + before['observations'] == audit['plays']
assert repeated['new_source_rows'] == repeated['new_songs'] == 0
assert repeated['playback_events'] == merged['playback_events']
assert sum(audit['outcomes'].values()) == 1298079
print(json.dumps(audit, indent=2))
PY
