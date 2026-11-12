# Historical archive import rehearsal

The importer writes to **`rp_history` only**. `public.song`, `public.playback_event`,
observation counters, and the live collector's durable checkpoint retain their
existing meaning during this staging import. Production currently retains that
separation. The subsequent [local real-table merge](rp-history-real-table-merge.md)
reconciles historical broadcasts into `public.song` and `public.playback_event` for
review through the actual Records page and statistics.

## Data rules

- `rp_history.source_play` preserves each distinct normalized identity plus upstream
  payload, including unknown fields. A SHA-256 key includes channel, source event,
  RP song ID, broadcast instant, and the entire upstream `data` object. Fetch time
  and pagination URL are provenance and do not change identity.
- Exact source variants are inserted once. Differing timestamps **or payloads** for
  the same `(channel, event_id)` are retained for analysis. These rows must not be
  interpreted as a resolved count of actual broadcasts.
- Missing artist/title/album and zero song IDs are flagged in `metadata_issues`.
  They remain archived without manufacturing valid application songs.
- Invalid identities or inconsistent timestamps go to `rejected_row` with the
  original JSON line and reason. Unexpected database failures abort the shard.
- Each compressed playback shard is one transaction, bounded by the collector's
  500-page rotation (usually about 10,000 rows). Accepted rows, rejected rows, and
  the file ledger commit together. An interrupted transaction can be replayed;
  completed files are skipped. An advisory lock serializes file commits and a
  second importer rechecks completion under that lock.
- The collector lock is held during verification and import. The manifest must be
  exhausted, verification must cover every playback shard, and all playback shard
  checksums are checked before any database writes. Keep the raw-page archive and
  verification report alongside the normalized files for provenance.

## Local database

The rehearsal uses PostgreSQL 17 in `rparadise-history-rehearsal`, with persistent
Docker volume `rparadise-history-rehearsal-data`. Port `55432` is bound to loopback.
Database, username, and local-only password are `history_rehearsal`. No application,
collector, or enrichment worker is launched with this container.

The production snapshot is stored in the ignored, restricted directory
`.secrets/backups/history-rehearsal-20261112/production.dump`. It was downloaded
using the production PostgreSQL container's `pg_dump -Fc --no-owner --no-acl` over
SSH, then restored locally with `pg_restore --exit-on-error`. Production remained
online. The snapshot initially contained 6,554 songs and 11,329 observed plays.

The full local rehearsal on November 12, 2026 processed 132 files and 1,298,136
source rows: 1,298,079 distinct source variants, 57 exact duplicates, and zero
rejected rows. There are 733 event references with multiple retained variants
(842 additional variants). Metadata issues affect 1,034 retained rows. Per-channel
stored counts are Main 453,119, Mellow 471,821, and Rock 373,139.

The archive schema occupied 2,222,481,408 bytes (about 2.22 GB). Ordered full-row
fingerprints for both application tables matched before and after import. A repeat
run skipped all 132 files and committed zero new files. Existing API health,
pagination totals, current songs, most-played songs, and `Cache-Control: no-store`
checks passed. An indexed Main-channel 2024 date-range query returning 25 rows took
0.07 ms locally; this is a warm local measurement, not a production latency promise.

Verification passed: 18 collector tests, 10 importer tests including PostgreSQL
rollback/resume and simultaneous writers, backend Maven verification (203 tests,
30 environment-gated skips), and disposable PostgreSQL backend integration
verification (207 tests, no skips). Generated audit, API smoke, and query-plan
reports are retained in the ignored archive directory.

Inspect with a database client at `127.0.0.1:55432` or:

```sh
docker exec -it rparadise-history-rehearsal psql -U history_rehearsal -d history_rehearsal
```

## Run and resume

Verify a stopped archive first:

```sh
python3 scripts/archive-rp-history.py verify --output data/rp-history/all-available
```

Run a pilot, continue, then repeat to check that completed files are skipped:

```sh
python3 scripts/import-rp-history.py --archive data/rp-history/all-available --max-files 1
python3 scripts/import-rp-history.py --archive data/rp-history/all-available
python3 scripts/import-rp-history.py --archive data/rp-history/all-available
```

Defaults target the local rehearsal container/database/user above. Explicit
`--container`, `--database`, and `--user` arguments select another target; they are
write destinations. The script uses that container's `psql` and needs no additional
Python packages. The importer applies the additive schema migration automatically.

Generate a reconciliation report:

```sh
docker exec -i rparadise-history-rehearsal psql -X -At -v ON_ERROR_STOP=1 \
  -U history_rehearsal -d history_rehearsal < scripts/audit-rp-history.sql
```

Before and after the import, compare counts and ordered row fingerprints for
`public.song` and `public.playback_event`, including the latest per-channel events.
Compare imported/rejected/duplicate totals with the archive manifest, and check
per-channel date ranges. A second import should commit zero new files. The
reconciliation report distinguishes source variants from actual broadcast counts.

## Tests

```sh
python3 -m unittest discover -s scripts -p 'test_import_rp_history.py' -v
bash scripts/test-import-rp-history-postgres.sh
./mvnw -B -ntp verify
bash scripts/test-records-postgres.sh
```

The importer PostgreSQL suite creates and removes its own disposable database.
It exercises exact duplicates, conflicting variants, unusual text, metadata flags,
rejections, rollback, resume, and repeat imports. Backend integration tests also
use a separate disposable database, never the production clone.

## Production rollout

Production import completed on November 12, 2026 after the local rehearsal. All
132 files committed, with exactly the same channel counts, date ranges, metadata
issue counts, conflicting variants, duplicate count, and zero rejections as local.
The production schema occupied 2,221,236,224 bytes. A repeat production run skipped
all files and committed zero new files; `ANALYZE` completed afterward. Application
health remained UP, the public playback API retained `no-store`, and collector
freshness was confirmed on every channel. About 30 GiB remained free afterward.

The production maintenance directory is
`/home/ubuntu/rparadise-history-import-20261112`. It retains the checked package,
pilot/full/repeat logs, source shards, and `production-import-audit.json`. A local
copy of that report is kept with the archive. The fresh pre-import backup is
`.secrets/backups/history-rehearsal-20261112/production-before-import.dump`.
No application image or existing observation data was replaced.

To resume or repeat on the VM:

```sh
cd /home/ubuntu/rparadise-history-import-20261112
sudo python3 scripts/import-rp-history.py --archive data/rp-history/all-available \
  --container rparadise-tracker-postgres-1 --database radioparadise --user radioparadise
```

The full reconciliation query scans the archive and took several minutes on the
production VM. For quick progress checks, query `rp_history.import_file` instead.
Use SSH keepalives for long maintenance commands, as in the deployment guide.

Use the tested archive, migration, and importer against the current production
database after reviewing the rehearsal results. Take a fresh backup first, confirm
free disk space against measured local archive growth, and monitor application
health, collector freshness, database load, and disk growth during the import.
Local timings are not a throughput guarantee for the smaller production VM.
Keep transactions bounded and run the same read-only reconciliation report there.
After importing, run `ANALYZE rp_history.source_play` to update planner statistics.

The local database is a rehearsal artifact. Replacing production with it would
discard plays collected since the snapshot. Import source variants into the current
production database instead. The new schema is independent of the application,
so existing application code can continue operating while archive files import.

Stopping or retrying the importer does not require stopping the application.
Committed files remain durable and the next run resumes from the ledger. Since the
existing application does not read `rp_history`, an import problem can be investigated
with that schema left in place. Restoring the entire production database would
also roll back new tracker observations, so it is not the default archive rollback.
