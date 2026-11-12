# Local merge into application tables

The local runner only accepts the labeled `rparadise-history-rehearsal` container
and the `history_rehearsal` database. Production uses the separate maintenance
runner described below after the reviewed application image has been deployed.

## Completed local result — November 12, 2026

| Result | Rows |
| --- | ---: |
| Newly inserted historical playback events | 1,285,862 |
| Broadcasts reconciled to existing observations | 9,481 |
| Held: conflicting event reference | 1,575 |
| Held: conflicting channel/broadcast timestamp | 131 |
| Held: incomplete/invalid metadata | 1,030 |
| Final `public.playback_event` total | 1,297,191 |
| Final `public.song` total | 13,937 |

All 11,329 original observations were preserved. The merge added 7,383 channel
song rows; the channel-independent library contains 10,699 exact song identities.
History begins at `2023-03-26T14:12:11Z`. All 1,298,079 staged source variants have
a recorded outcome, and the 2,736 held rows remain available for review.

Original observation identities/times, existing enrichment metadata, and live
checkpoints passed the transaction's preservation checks. The post-merge audit
found zero statistics mismatches and zero invalid indexes; song play counts sum
to 1,297,191. A repeat run added zero sources or songs and left playback totals
unchanged. The 2024 Istanbul-calendar filter returned 363,148 imported broadcasts
through both the database and the actual UI/API.

The first large run exposed an overly broad overlap check. It was cancelled before
commit, its rollback was confirmed against original full-row fingerprints, and
the check was restricted to the observation date range before the successful run.

Verification passed: 10 staging importer tests, 10 merge PostgreSQL tests, backend
Maven verification, all 208 backend/PostgreSQL integration tests, all 24 frontend
tests, the frontend build, eight live local API checks, and browser review of the
historical records. Local API audit and repeat-run reports are kept alongside the
generated merge report in the ignored archive directory.

## Merge rules

- Existing observations retain their event IDs, song IDs, and timestamps. Existing
  song identities and all enrichment metadata remain intact.
- Source variants with a repeated `(channel, event_id)` reference are held as
  `ambiguous_event`. Multiple event IDs at the same channel/time are held as
  `ambiguous_broadcast_time`. Missing required metadata, nonpositive RP song IDs,
  or values exceeding application column limits are held as `invalid_metadata`.
  Every source row remains available in the archive.
- An eligible broadcast matches an existing observation on channel and RP song ID,
  or exact artist/title/album identity, within its broadcast interval. The interval
  ends at the next source timestamp or duration plus 60 seconds, whichever is
  earlier; a missing/zero duration uses a 20-minute fallback plus 60 seconds.
- One matching observation is reused and counted once. Multiple matches, or a
  matching observation within 60 seconds just outside the interval, are held as
  `ambiguous_observation`. This avoids inflating counts when timing is uncertain.
- Unmatched eligible broadcasts become real `public.playback_event` rows with
  broadcaster timestamps in the existing timestamp column and a unique
  `rp_source_key`. Songs use the application's exact artist/title/album/channel
  identity. New songs get archive metadata, duration, RP song ID, and artwork URLs;
  existing songs keep their metadata.
- `rp_history.playback_merge` records each source row's outcome and playback link.
  Held source records are not counted as confirmed broadcasts. Exact duplicate
  source variants were already deduplicated during staging.
- Counts and first/last played timestamps are rebuilt from the resulting physical
  playback rows. These statistics describe stored plays, not proof of listening.
- The API's additive `timeSource` field distinguishes `broadcast` from `observed`.
  The Records page shows that distinction and uses "Played at" for the combined
  timeline. Original observations keep their original timestamp meaning.

## Execution and retry

Stop the local backend and close other database clients first. The runner rejects
an unexpected container label or other connected clients. Back up the local
application tables before running:

```sh
python3 scripts/merge-rp-history.py
```

The SQL merge takes write locks on the real song/playback tables and commits as
one transaction. It checks original observations, enrichment metadata, live channel
checkpoints, and event-derived statistics before committing. A failure rolls back
the merge; successful source decisions make repeat runs harmless. Production
requires the maintenance wrapper so every application writer is paused first.

The local pre-merge application backup is
`.secrets/backups/history-rehearsal-20261112/before-real-table-merge.dump`.
The original production snapshot remains available separately. The generated merge
report is `data/rp-history/all-available/local-real-table-merge.json`.

Inspect held rows and their original source payloads:

```sql
SELECT m.outcome, a.channel, a.event_id, a.broadcast_at, a.source_record
FROM rp_history.playback_merge m
JOIN rp_history.source_play a USING (source_key)
WHERE m.playback_event_id IS NULL
ORDER BY m.outcome, a.channel, a.broadcast_at;
```

## Review locally

The database is at `127.0.0.1:55432`, database/user/password `history_rehearsal`.
Use this explicit connection for the backend; its normal defaults still use port
5432. Build with Java 25, then start a frozen review backend with collection disabled:

```sh
DB_URL=jdbc:postgresql://127.0.0.1:55432/history_rehearsal \
DB_USERNAME=history_rehearsal DB_PASSWORD=history_rehearsal \
java -jar target/radio-paradise-tracker-1.0.0.jar \
  --server.port=8081 --radioparadise.api.channels= --spring.jpa.hibernate.ddl-auto=validate
```

In another terminal:

```sh
npm --prefix frontend run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

Open `http://127.0.0.1:5173/#/records?tab=plays`. Historical date filters, most-played
statistics, and the combined song library now use the real application tables.
Run `ANALYZE public.song; ANALYZE public.playback_event;` after the merge to update
query statistics before reviewing performance.

## Verification

```sh
bash scripts/test-import-rp-history-postgres.sh
./mvnw -B -ntp verify
bash scripts/test-records-postgres.sh
npm --prefix frontend test
npm --prefix frontend run build
```

The merge PostgreSQL suite tests overlap deduplication, metadata preservation,
artwork/duration population, replay boundaries, RP identity matching, held conflicts,
checkpoint protection, rollback, and repeat imports in a disposable database.

## Production maintenance

Ship the reviewed provenance API/UI through the existing protected deployment
workflow first. It remains compatible with an archive-only production database.
Keep a fresh off-host full backup, and copy the committed SQL, audit, and wrapper
to the production history maintenance directory. Run with the deployed commit SHA:

```sh
bash scripts/run-production-history-merge.sh \
  /home/ubuntu/rparadise-history-import-20261112/real-table-RUN_ID DEPLOYED_COMMIT_SHA
```

Use a transient systemd unit for the actual long run so an SSH interruption does
not interrupt maintenance. The wrapper checks the expected healthy application
image and archive size, pauses the retry timer/service and application, takes a
final public-schema snapshot, then runs the same atomic merge and a repeat run.
The 768 MiB production memory-limit rehearsal was killed during the large merge
and recovered with the transaction rolled back. During maintenance, the wrapper
therefore temporarily raises PostgreSQL's limit to 2 GiB after stopping the app.
It updates planner statistics and checks actual row counts, provenance, indexes,
and event-derived counters. A failure before SQL commit rolls back the merge.
An exit trap restores the database's original memory/swap limits, restarts the
application, checks health, and restores the retry timer
on both successful and failed runs. Do not run the merge SQL against active writers.

The collector is stopped during maintenance; its normal observation-gap limitation
applies. Existing observations and live checkpoint identities must remain intact.
The run directory retains the snapshot, checksums, before/merge/repeat/audit JSON,
phase, log, and exit code. Copy the final public-schema snapshot off the VM too.
