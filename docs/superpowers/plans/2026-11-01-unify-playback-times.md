# Unify playback timestamps and summaries

> Execute inline using executing-plans and test-driven-development. The user authorized correcting production records and their dates; truncation is a fallback only if repair cannot succeed.

**Goal:** Store song and playback timestamps with the same absolute-time semantics and make song summaries match recorded history exactly.

**Architecture:** Use Instant/timestamptz for both entities. Capture one microsecond-precision Instant per observed play. Migrate legacy Istanbul wall times explicitly, then derive song first/last/count from existing playback events. Preserve all events and song IDs. Stop the collector for the brief migration/deploy transition.

**Constraints:** Preserve concurrent observability edits. Keep UTC API contract and browser-local presentation. Back up live database before mutation. No truncation while repair succeeds. Use existing checkout and deploy only the changed application inputs over the current production source.

## Tasks

- [x] 1. Regression test: a newly observed and a repeated play must share exactly one timestamp with the song summary. Watch RED; change Song, SongService, TrackRecord and relevant fixtures to Instant; run suite.
- [x] 2. Add transactional SQL migration and a real PostgreSQL test covering Istanbul-to-UTC conversion, precise summary rebuilding, rerun safety, and rejection of orphan songs. Validate fresh JPA storage types and exact persisted equality, including A→B→A.
- [x] 3. Review the complete diff, back up live DB and prior app image/source, build updated image, stop collector, migrate and start new image. Verify all three timestamp types, counts, first/last equality, API, health, and new collector events. Refresh both IntelliJ table views with one timezone.

## Review focus

- Old wall times must be interpreted as Europe/Istanbul, never relabeled UTC.
- DTOs must not apply the old additional Istanbul-to-UTC conversion after migration.
- Sub-microsecond rounding must not create cross-table differences.
- Migration cannot run alongside the old collector or silently discard unreferenced songs.
- A restart must not add an extra event; later replay must increment count and retain first time.

## Ledger

- Initial audit: all 38 existing latest events match song instants within 80ms after explicit conversion; three legacy summaries retain older first times/counts. IntelliJ applies global timezone conversion to naive timestamps, causing 16:xx display. No stored 16:xx corruption found.
- Regression: shared-instant tests failed twice against the old implementation, then passed after the Instant change. Migration tests failed twice against a no-op migration, then passed with the SQL implementation.
- Verification: `bash scripts/test-records-postgres.sh` passed all 33 tests, no skips, on PostgreSQL 17 (2026-11-01 11:16 UTC).
- Independent final review: no Critical, Important, or Minor defects found. Restore both the database backup and old image on rollback. Unrelated observability edits and preexisting multi-instance collection are outside this repair; production runs one collector.
- Production migration committed at approximately 14:27:54 Istanbul. All 51 original events were unchanged (count and row fingerprint match); all 51 song summaries rebuilt. New application healthy, startup completed at 14:28:13.
- Operational issue: the first SSH stdin-fed deployment script stopped after pg_dump consumed the remaining stdin; resumed from a verified backup using an uploaded script file. No database mutation occurred in the interrupted attempt. Collection paused for several minutes during the transition; no missing events were fabricated.
- Final live audit at 14:31:28 Istanbul: 56 songs, 56 events, zero summary mismatches, zero unrelated songs, zero dangling events, zero future song dates. All three columns are timestamptz. Five new events since migration have exactly equal song/event timestamps. Public API comparison: 22 matching recent pairs.
- IntelliJ: global temporal display Europe/Istanbul; refreshed both original table queries and left a read-only side-by-side consistency query open. Both fields display identical +03:00 values; all ten displayed comparisons true.
- Backups: server `/home/ubuntu/rparadise-backups/20261101-unify-playback-times`; local `.secrets/backups/unify-playback-times/20261101/oracle-before-and-audit.tar.gz` and `post-migration-audit.txt`. Old image retained as `rparadise-tracker-app:before-time-repair-20261101`.
