# Radio Paradise 2026 broadcast history: findings and plan

Status: research and proposed implementation only. No importer, application changes,
database writes, or production actions were performed.

Later acquisition finding (2026-11-10): the full downloader encountered Main event
2881619 twice in the same API page, with `play_time` values 1780420600000 and
1780421360000. Thus `(channel, event)` is an event reference, not a proven unique
source-row key. The archive preserves both variants and counts repeated event rows.
Any import uniqueness constraint proposed below needs to account for this evidence;
do not silently discard a differing timestamp or payload.

## Recommendation

Proceed with a small archive extraction pilot, followed by a resumable 2026 backfill
for Main (0), Mellow (1), and Rock (2). Live probes demonstrate early January 2026
records and records before 2026 on every supported channel. This makes a year-to-date
archive plausible, but does not establish uninterrupted coverage of the whole year.

Here, “2026 songs” means songs **broadcast during 2026**, regardless of release year.
The response's `year` field is the album/song release year, not the broadcast year.
Both individual plays and a distinct-song catalogue can be derived from the archive.
As of November 10, only 2026 year-to-date is available; the remaining year requires
continued collection.

## Verified upstream behavior

Inspected the public HTML, its linked `main.9cbff9c660809ff8.js` bundle, and JSON
responses on 2026-11-10 around 09:28–09:33 UTC. Requests used no account, cookies,
player identifier, or credentials.

The website calls these endpoints:

```text
GET https://api.radioparadise.com/api/nowplaying_list_v2022
    ?mode=wip-channel&chan=0

GET https://api.radioparadise.com/api/nowplaying_list_more_v2022
    ?chan=0&list_offset=<oldest event from previous page>
```

`list_offset` is an event cursor, not a row count or Unix timestamp. The site's
JavaScript passes the last returned row's `event` as the next cursor. A tested
continuation excluded the cursor row and returned older events. Each channel needs
its own cursor. Sparse cursor probes also returned older records without first
walking every intervening page.

The recent endpoint returned 30, 29, and 30 rows for channels 0, 1, and 2 despite
`list_num=100`. Do not assume the requested count is honored or that 100 rows are
available. Tested continuation pages returned 20 rows. Retention, supported page
sizes, rate limits, and archive completeness have no verified contract here.

### Reproducible archive samples

Times below are UTC, converted from the first returned row's `play_time`.

| Channel | Endpoint `list_offset` | First event | Broadcast time | Artist — title |
| --- | ---: | ---: | --- | --- |
| Main (0) | 2821000 | 2820999 | 2026-01-02 04:10:39 | Cracker — Trials & Tribulations |
| Mellow (1) | 2830000 | 2829999 | 2026-01-03 03:46:43 | Sofi Tukker — Bread (butter version) |
| Rock (2) | 2732000 | 2731999 | 2026-01-05 07:36:25 | Larkin Poe — If God Is a Woman |
| Main (0) | 2820000 | 2819999 | 2025-12-30 15:43:06 | Obongjayar — Give Me More |
| Mellow (1) | 2820000 | 2819999 | 2025-12-09 18:30:21 | SYML — Carry No Thing |
| Rock (2) | 2720000 | 2719999 | 2025-12-04 22:15:27 | King Buffalo — Regenerator |
| Main (0) | 2500000 | 2499999 | 2023-10-27 01:42:37 | Sam Phillips — How to Dream |

The repeated event 2819999 on Main and Mellow refers to different songs and times.
Use `(channel, event)` for upstream playback identity, never `event` alone.
Event values have gaps; gaps in numeric IDs alone do not demonstrate missing music.
A much older Main probe at cursor 2000000 returned an empty song list, which does
not establish an exact retention boundary.

### Available fields

| Field | Meaning / proposed use |
| --- | --- |
| `event`, `chan` | Per-channel playback identity and pagination cursor |
| `song_id` | RP catalogue identity; repeated broadcasts reuse song IDs |
| `play_time` | Broadcaster-provided playback time, Unix milliseconds; parse as `long` and `Instant.ofEpochMilli` |
| `duration` | Duration in milliseconds, represented as a numeric string in samples |
| `artist`, `title`, `album`, `year` | Track metadata; retain the upstream snapshot |
| `cover`, `cover_med`, `cover_small` | Artwork paths resolved against response `cover_base_url` |
| `listener_rating`, `ratings_num`, `ratings_dist` | Rating metadata returned with the request; not verified as historical snapshots |

This describes channel broadcast history, not proof that a particular user listened.
Lyrics, ISRC, external recording links, listener counts, and historical rating changes
are not established by these playlist responses.

## Implications for this application

`RadioParadiseClient` already fetches the recent endpoint, but
`RadioParadiseResponse` does not map `event`, `chan`, or `play_time`.
`SongTrackerScheduler` saves only the first/current song. `SongService` creates an
event with `Instant.now()` and deduplicates against the latest observed channel song.
It is unsuitable as a historical import entry point: it would assign present-day
timestamps, apply current-play deduplication, and update observation statistics.

`PlaybackEvent.observedAt` and the existing API's `playedAt` mean first observed by
the tracker. Existing song counts and first/last times derive from those observations.
An imported RP broadcast overlaps existing observations but usually has a different
timestamp. Exact timestamp equality cannot reconcile the two datasets.

Recommended first integration: store RP broadcast events separately, keyed by
`(channel, rpEventId)`, with `rpSongId`, `broadcastAt`, duration, metadata snapshot,
and import provenance. Keep existing observation rows and counters intact. Avoid
inserting archive-only tracks into the current `Song` table with fabricated
observation dates or counts. A nullable link to an existing song can be added when
the identity match is unambiguous.

Expose broadcast history and its statistics explicitly. If a unified history is
later desired, design a deliberate reconciliation and API transition; never add
imported plays to observed counts indiscriminately. Ratings fetched now should not
be presented as ratings measured at the historical broadcast time.

## Execution plan

1. **Pilot extraction, without application database writes.**
   Build a small resumable downloader that saves raw response pages and a manifest.
   Walk contiguous pages covering a full day on each mix and the January 1 boundary.
   Verify cursor advancement, returned channels, duplicate identities, valid times,
   missing metadata, and unusual/non-song rows. Establish response timing and size.
   Sparse probes in this research are evidence of reach, not a coverage audit.

2. **Download the 2026 archive.**
   Capture a fixed run cutoff, then paginate from each channel's latest response
   backward until crossing `2026-01-01T00:00:00Z`. Retain only events within
   `[2026-01-01T00:00:00Z, cutoff)`. Define this explicitly as a UTC year; a
   listener-local calendar year requires that timezone's midnight boundaries.
   Persist each page before advancing its checkpoint. Start at one request per
   second globally, honor `Retry-After`, back off on failures, and resume safely.
   Stop and report unexpected empty pages, repeated cursors, channel mismatches,
   and out-of-order timestamps rather than declaring coverage complete.

3. **Audit before import.**
   Report plays, distinct RP song IDs, daily/channel counts, earliest/latest times,
   duplicates, invalid rows, and suspicious time gaps. Preserve rejected rows and
   reasons. Do not infer exact completeness from ID continuity or fill missing
   timestamps synthetically. Re-fetch overlapping boundary pages to check stability.

4. **Add idempotent archive persistence and a broadcast history API.**
   Use a new migration and a database uniqueness constraint on `(channel, rpEventId)`.
   Insert in bounded transactions and commit checkpoints with their corresponding
   data. Preserve upstream IDs, UTC instants, and raw metadata. Make repeat imports
   harmless, invalidate relevant bounded caches, and keep the live collector's
   checkpoint and observation transactions unaffected.

5. **Continue collection and present historical statistics.**
   Poll the playlist and import every unseen event through the same idempotent path,
   using continuation pages to bridge outages. Maintain a separate archive ingestion
   checkpoint. Provide a clearly named broadcast history view, date/channel/search
   filters, distinct-song counts, and most-broadcast tracks. Use broadcaster times
   for those statistics and retain the tracker observation meaning in existing APIs.

## Sizing and validation

A rough planning estimate is 300,000–400,000 music-play rows across three mixes for
2026 year-to-date, implying roughly 15,000–20,000 continuation requests at 20 rows
per page. This is an extrapolation from sampled event ranges and song density,
not an archive count. One request per second requires at least 4–6 hours plus
network latency and retries. Measure the pilot before committing to a schedule or
storage estimate. Do not launch bulk artwork or lyrics downloads with the backfill.

Implementation tests should cover millisecond parsing, UTC/year boundaries,
per-channel event collisions, consecutive plays of the same song, cursor stalls,
retries, crash recovery, duplicate pages, overlapping imports, malformed rows, and
preservation of existing live collection and statistics. Database tests must use
the repository's disposable PostgreSQL harness.

Required checks when implementing: `./mvnw -B -ntp verify`,
`bash scripts/test-records-postgres.sh` for persistence/query/migration work, and
frontend tests/build if the history UI changes. This research-only change does not
modify executable code and did not run those suites.
