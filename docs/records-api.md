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

Storage: playback events use `Instant`/PostgreSQL `timestamp with time zone`.
The legacy Song `first_played`/`last_played` columns still contain Istanbul wall
time (`LocalDateTime`/`timestamp without time zone`). The API interprets those
values in `Europe/Istanbul` and converts to UTC, preserving their actual instant;
it does not relabel the stored wall time as UTC or perform a schema migration.

`playedAt` means first observed by the tracker, whose normal polling interval is
30 seconds; it is not an exact broadcaster start time. Downtime may leave gaps.
An ongoing track is deduplicated across application restarts via its latest
persisted channel event. Full replays during downtime cannot be inferred when
the same track is still current after recovery. Existing track counts include
older observations and need not equal the newly collected event count.

The original `/api/songs`, `/api/songs/recent`, `/api/songs/most-played`, and
`/api/songs/channel/{channelId}` endpoints remain compatible.

## Verification

Run `bash scripts/test-records-postgres.sh` to start an isolated PostgreSQL 17
database, run all backend tests plus the real HTTP/database integration tests,
and remove the disposable container. Ordinary Maven verification runs the unit
tests and explicitly skips the PostgreSQL-only class unless
`RECORDS_DB_TESTS=true` is set. No additional test dependencies are required.

Run `npm test && npm run build` inside `frontend` for the UI checks.
