# Public Radio Paradise history archive

The archive downloads every playback exposed by the website's public backward
pagination for Main (0), Mellow (1), and Rock (2). It has no date limit and does not
access or modify the application database. Each channel starts at the current
playlist at its initial fetch and walks backward to the API's observed boundary.
New plays after that initial fetch are outside this snapshot.

## Format

Use **UTF-8 JSON Lines compressed with gzip** (`.jsonl.gz`). This avoids loading a
huge JSON array into memory and can be read with standard gzip/JSON tools. Files
rotate every 500 API responses. Each gzip file contains concatenated gzip members,
which Python's gzip reader and standard gzip decompressors read sequentially.

Two synchronized versions are retained:

- `raw-pages`: one complete parsed API response per line, preserving every upstream
  field, request URL, input cursor, fetch time, selected HTTP headers, and the SHA-256
  of the original HTTP body. This preserves JSON values, not byte-for-byte HTTP bodies.
- `plays`: one playback per line, with stable IDs and a normalized UTC broadcast
  time. The `data` object retains the entire upstream song object without dropping
  unknown fields. No metadata enrichment is performed during acquisition.

Example playback, abbreviated for readability:

```json
{
  "schema_version": 1,
  "channel": "0",
  "event_id": "2820999",
  "song_id": "12345",
  "broadcast_at": "2026-01-02T04:10:39.000Z",
  "fetched_at": "2026-11-10T12:00:00.000Z",
  "source_url": "https://api.radioparadise.com/api/nowplaying_list_more_v2022?chan=0&list_offset=2821000",
  "data": {
    "event": "2820999",
    "chan": 0,
    "song_id": "12345",
    "play_time": 1767327039000,
    "artist": "Example artist",
    "title": "Example title"
  }
}
```

Use **`(channel, event_id)`** as a source event reference, not a guaranteed unique
source row. The full crawl found one event twice in a single Main page with different
`play_time` values. Both rows are preserved, and `repeated_event_rows` counts these
additional rows. Total plays in the manifest means source rows; deciding which
variants represent actual separate broadcasts is deferred until import analysis.
The same song can appear many times, and the same event number can refer to
different plays on different channels. `broadcast_at` comes from the broadcaster's millisecond timestamp; it is
not our tracker's observation time. Invalid upstream timestamps remain in `data`
and normalize to `null`, with a count in the manifest. Ratings returned during a
historical request are not established as historical rating snapshots.

## Files and operation

The default location used for this acquisition is ignored by Git:

```text
data/rp-history/all-available/
  manifest.json
  process.json
  download.log
  collector.lock
  verification.json
  channel-0/
    checkpoint.json
    raw-pages/000000.jsonl.gz
    plays/000000.jsonl.gz
  channel-1/...
  channel-2/...
```

`manifest.json` reports status, counts, and UTC date ranges. `verification.json`
contains the last verified snapshot's counts, date ranges, and SHA-256 hashes for
every compressed data file; a pilot verification does not cover subsequent downloads.
An unbounded run automatically verifies the archive when all channels reach their
observed boundaries. The process PID is recorded in `process.json`.

Run a small pilot, verify it, then start or resume the full acquisition:

```sh
python3 scripts/archive-rp-history.py collect --output data/rp-history/all-available --max-pages 15
python3 scripts/archive-rp-history.py verify --output data/rp-history/all-available
python3 scripts/archive-rp-history.py collect --output data/rp-history/all-available --detach
```

The detached process writes `download.log` and survives closing its launching
terminal. The computer must remain awake and connected for requests to progress.
Only one collector can own an archive directory. To pause, send `SIGTERM` to the
PID in `process.json`; it completes any current page and updates its checkpoint.
Running the same collection command resumes from committed cursors. Do not edit,
move, or truncate an active archive. Verify only while the collector is stopped.

The collector makes at most one request start per second globally, retries transient
HTTP/network failures with backoff, and honors `Retry-After`. Repeated failures mark
the manifest `failed` and preserve progress for a later resume. An explicit request
rejection, mismatched channel, or non-advancing event cursor stops collection;
structurally rejected JSON responses are retained under `rejected/` for inspection.

Each page's raw and normalized streams are flushed before the checkpoint advances.
On restart, bytes appended after the last committed checkpoint are truncated and
that page is fetched again. Checkpoints must be backed up with the corresponding
data files. Use a stable stopped snapshot for copying or sharing the archive.

`exhausted` means three consecutive empty responses at the observed per-channel
boundary. It does not prove that RP retains every historical broadcast, and event
number gaps alone do not prove missing music. The website may change or limit its
API. The downloader deliberately does not seek behind an empty boundary and
silently skip an unknown range.

The snapshot completed on November 11, 2026, with **1,298,136 source rows across
64,919 API pages**. All three channels reached their observed empty-page boundary,
and the stopped snapshot was verified again before import on November 12.

| Channel | Source rows | Earliest broadcast (UTC) | Latest broadcast (UTC) |
| --- | ---: | --- | --- |
| Main (0) | 453,156 | 2023-03-26 | 2026-11-10 |
| Mellow (1) | 471,841 | 2023-04-02 | 2026-11-10 |
| Rock (2) | 373,139 | 2023-04-05 | 2026-11-10 |

These are verified snapshot counts and observed boundaries, not proof of complete
station retention. See [the import runbook](rp-history-import.md) for the separate
broadcast archive schema, duplicate/variant handling, and production-clone rehearsal.

Run the offline regression suite with:

```sh
python3 -m unittest discover -s scripts -p 'test_archive_rp_history.py' -v
```
