# Records and playback history API

Base URL: `https://rparadise.138.2.168.194.sslip.io`

UI: `/#/records` (All Tracks), `/#/records?tab=plays` (Playback History).

## Endpoints

- `GET /api/records/tracks`: one row per artist/title/album/channel combination,
  with all stored track metadata, `id`, `firstPlayed`, `lastPlayed`, `playCount`.
- `GET /api/records/plays`: one row per newly observed play, with track metadata,
  event `id`, `songId`, and `playedAt`. No historical timestamps are fabricated.

Both return:

```json
{
  "items": [],
  "page": 0,
  "size": 25,
  "totalElements": 0,
  "totalPages": 0,
  "historyStartedAt": null
}
```

`historyStartedAt` is the earliest stored event globally, regardless of the
current filters; null until the first event, and always null for tracks.

## Query parameters

| Parameter | Meaning |
| --- | --- |
| `page` | Zero-based page, default 0, maximum 1000000 |
| `size` | 25 (default), 50, or 100 |
| `sort` | Track column, default `lastPlayed`; history defaults to `playedAt` |
| `direction` | `asc` or `desc` (default) |
| `q` | Case-insensitive literal substring in title, artist, or album; maximum 200 characters |
| `channel` | `0` Main Mix, `1` Mellow Mix, `2` Rock Mix; omitted/empty means all |
| `from`, `to` | Plays only: inclusive calendar dates, `YYYY-MM-DD`, in `timeZone` |
| `timeZone` | Timezone for history date boundaries, e.g. `America/New_York` or `UTC`; omitted defaults to `Europe/Istanbul` for existing API clients. The UI explicitly sends the browser's timezone. |

Track sort fields: `id`, `title`, `artist`, `album`, `year`, `channel`,
`firstPlayed`, `lastPlayed`, `playCount`.

Play sort fields: `id`, `title`, `artist`, `album`, `year`, `channel`, `playedAt`.
ID breaks sorting ties. Paging reflects current data rather than a frozen
snapshot; a new play can change totals/order between requests. Invalid supported
parameter values return HTTP 400 with an `error` message. An out-of-range page
returns an empty `items` array with the real total.

Examples:

```text
/api/records/tracks?page=0&size=25&sort=lastPlayed&direction=desc
/api/records/tracks?channel=2&q=Muse&sort=playCount&direction=desc
/api/records/plays?page=0&size=50&sort=playedAt&direction=desc
/api/records/plays?channel=1&from=2026-11-01&to=2026-11-01
/api/records/plays?from=2026-11-01&to=2026-11-01&timeZone=America/New_York
```

All API timestamps (`firstPlayed`, `lastPlayed`, `playedAt`, `historyStartedAt`)
are ISO 8601 UTC instants (`Z`), including the legacy `/api/songs` endpoints.
The UI converts them to the browser's timezone, includes seconds, and shows the
detected timezone name. Date filters use that same timezone and respect its
daylight-saving transitions (calendar days can be 23 or 25 hours).

## Storage and migration

Both song timestamps and playback events use `Instant`/PostgreSQL
`timestamp with time zone`. Each new play uses one shared timestamp with
microsecond precision. Song first/last/count summarize the stored events.

Before upgrading an existing installation, stop the collector and back up the
database. Run `scripts/migrations/2026-11-01-unify-playback-times.sql` using
`psql -v ON_ERROR_STOP=1` before starting the new application. It explicitly
interprets legacy wall times in `Europe/Istanbul`, preserves every event, and
rebuilds song summaries from the actual history. It aborts on unrelated songs
instead of discarding them. The migration is safe to rerun. Do not rely on
Hibernate's automatic column conversion for this upgrade.

For an existing database, apply
`scripts/migrations/2026-11-02-add-song-recency-indexes.sql` with
`psql -X -v ON_ERROR_STOP=1` before deploying the song recency index annotations.
Run it with autocommit enabled, without `--single-transaction` or a surrounding
transaction. It builds the global and channel-specific last-played indexes
concurrently while the collector remains running. New databases receive the same
indexes through the `Song` mapping.

After running the script, verify that both indexes are valid and ready:

```sql
SELECT c.relname, i.indisvalid, i.indisready, pg_get_indexdef(i.indexrelid)
FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
WHERE i.indrelid = 'song'::regclass
  AND c.relname IN ('song_last_played_id_idx', 'song_channel_last_played_id_idx');
```

Both rows must have `indisvalid` and `indisready` set to true and the expected
column order. The script can be rerun after a successful build. If a failed
concurrent build leaves an invalid index, `IF NOT EXISTS` will skip it; inspect
the failure and remove that invalid index with `DROP INDEX CONCURRENTLY` before
retrying. See the [index review](database-index-review.md) for the query analysis.

`playedAt` means first observed by the tracker, whose normal polling interval is
30 seconds; it is not an exact broadcaster start time. Downtime may leave gaps.
An ongoing track is deduplicated across application restarts via its latest
persisted channel event. Full replays during downtime cannot be inferred when
the same track is still current after recovery. Each song's count equals its
stored playback event count; first/last timestamps equal the earliest/latest
event for that song. The migration drops legacy summary-only counts and dates
that have no corresponding event, while preserving the event history itself.

The original `/api/songs`, `/api/songs/recent`, `/api/songs/most-played`, and
`/api/songs/channel/{channelId}` endpoints remain compatible.

## Verification

Run `bash scripts/test-records-postgres.sh` to start an isolated PostgreSQL 17
database, run all backend tests plus the real HTTP/database integration tests,
and remove the disposable container. Ordinary Maven verification runs the unit
tests and explicitly skips the PostgreSQL-only class unless
`RECORDS_DB_TESTS=true` is set. No additional test dependencies are required.

Run `npm test && npm run build` inside `frontend` for the UI checks.
