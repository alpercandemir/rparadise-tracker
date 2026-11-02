# Database index review

Reviewed 2026-11-02 at approximately 14:04 Europe/Istanbul against the production
PostgreSQL 17 database and the repository's query implementations. The initial
review was read-only. The two recommended recency indexes were subsequently
implemented and applied with user authorization; see the implementation below.

## Implementation and production verification

Applied on 2026-11-02 and verified at approximately 14:41 Europe/Istanbul:

- Built `song_last_played_id_idx` and `song_channel_last_played_id_idx` on
  production using `CREATE INDEX CONCURRENTLY`. Both have `indisvalid = true` and
  `indisready = true`, with the expected column order.
- Added the matching `Song` mapping definitions for schema creation/update, plus
  `scripts/migrations/2026-11-02-add-song-recency-indexes.sql` for existing databases.
- Recent 25 tracks now uses `song_last_played_id_idx`: sampled execution time
  0.040 ms, compared with the initial 0.468 ms scan/sort measurement.
- Latest 500 tracks now uses the same index: sampled execution time 0.191 ms,
  compared with the initial 1.051 ms scan/sort measurement.
- The sampled current-track lookup was 0.019 ms. At this small table size the
  planner chose the global recency index plus a channel filter; this does not
  imply the channel index is invalid or require forcing a different plan.
- The app remained healthy (`/actuator/health` returned `UP`) with its existing
  process running. No app restart was required.

These before/after samples are warm-cache individual executions, not a controlled
benchmark or an end-to-end latency guarantee. The change adds index maintenance
to last-played updates; no query semantics or song/playback data were changed by
the migration.

Validation passed: `./mvnw -B -ntp verify`, followed by
`bash scripts/test-records-postgres.sh` with 197 tests and zero failures, errors,
or skips. PostgreSQL tests verify the fresh Hibernate schema, concurrent migration
reruns, index validity/order, and preservation of an existing song. Maintenance
startup/shutdown also passed. The first sandboxed verification attempt failed
because Mockito could not attach its JVM agent; verification passed outside the
sandbox. Frontend files were unchanged.

## Recommendation

No additional index was urgent at the audited size and measured latency. However,
given the verified growth rate, the recommendation was to implement
`song(last_played DESC, id DESC)` and `song(channel, last_played DESC, id DESC)`
without waiting for a row-count threshold. Both are now applied. They match
frequent, existing queries that previously scanned and sorted the song table. Keep the existing
playback indexes. Add other indexes only when query frequency and measured
execution plans justify their storage and write cost.

A follow-up read-only check at 14:30 Europe/Istanbul on November 2 found 1,089
songs and 1,091 plays. The preceding 24 hours added 1,035 songs and 1,037 plays;
the preceding six hours added 263 songs and 265 plays. Both recent windows support
roughly 1,000 new rows per table per day. At that rate both tables reach 10,000
rows in approximately nine days. This supersedes the original recommendation to
wait until 10,000 song rows before reviewing these two indexes. Ten thousand rows
is not a performance cliff, and no larger-dataset benchmark was performed; the
reason to act soon is the predictable workload and growth, not a current incident.

## Production evidence

Counts below are exact at the audit snapshot; the collector continues writing.
Table sizes exclude indexes and include PostgreSQL table overhead.

| Table | Rows | Table size | Index size |
| --- | ---: | ---: | ---: |
| `song` | 1,072 | 568 kB | 152 kB |
| `playback_event` | 1,073 | 88 kB | 240 kB |

There were 1,037 playback events in the preceding 24 hours. If that short-term
rate continues, playback history adds about 31,000 events per 30 days and 379,000
per year. This is a planning estimate based on approximately one day of history,
not a capacity forecast. Song rows represent distinct artist/title/album/channel
combinations; replaying a known combination updates its song row and inserts a
new playback event. The song table therefore need not grow at the playback rate.

Representative read-only `EXPLAIN (ANALYZE, BUFFERS)` measurements:

| Query shape | Execution time | Observed plan |
| --- | ---: | --- |
| Recent 25 tracks, last played then ID | 0.468 ms | Song sequential scan and top-N sort |
| Dashboard latest 500 tracks | 1.051 ms | Song sequential scan and top-N sort |
| Current track for one channel | 0.520 ms | Song sequential scan, channel filter, top-N sort |
| Top 25 by play count then ID | 0.609 ms | Song sequential scan and top-N sort |
| Tracks ordered by artist then ID | 0.082 ms | Existing unique index and incremental sort |
| Tracks ordered by channel then ID | 0.355 ms | Song sequential scan and top-N sort |
| Track substring search for `love` | 1.562 ms | Song sequential scan and top-N sort |
| Latest 25 playback events with song metadata | 0.072 ms | Playback time index and song primary-key lookups |
| Playback channel and date range | 0.086 ms | Playback channel/time index and song primary-key lookups |
| Playback substring search for `love` | 1.769 ms | Filtered song scan, hash join, top-N sort |
| Playback ordered by artist then event ID | 0.195 ms | Song unique index, playback song index, incremental sort |
| Playback offset 1,000, limit 25 | 2.334 ms | Full join and sort before offset |
| Playback channel plus substring count | 1.666 ms | Filtered scans, hash join, aggregate |
| Dashboard channel/year counts | 0.516 ms | Song sequential scan and hash aggregate |

These are single executions with shared-buffer hits, not cold-cache benchmarks,
end-to-end API latencies, or p95 measurements. Representative queries select the
full entity columns used by the application. The production statement statistics
also show small execution times: the current full-column dashboard query shape
averaged 4.614 ms across 22 calls, with a maximum of 8.161 ms. Statistics include
earlier deployed query shapes and maintenance activity since the statistics reset
on November 1; they should not be treated as a controlled benchmark.

## Existing indexes and coverage

These definitions were verified in the live database, rather than inferred only
from JPA annotations.

| Existing index | Coverage |
| --- | --- |
| `song_pkey (id)` | Song details, metadata updates, ID sorting, and ID-based backfill batches |
| Unique `song(artist, title, album, channel)` | Collector's exact song lookup and duplicate protection; partially helps artist ordering |
| `playback_event_pkey (id)` | Playback identity and ID ordering |
| `playback_time_idx (observed_at DESC, id DESC)` | Default history ordering, date ranges, earliest-history lookup via backward scan |
| `playback_channel_time_idx (channel, observed_at DESC, id DESC)` | Channel history/date filtering and durable latest-play checkpoint |
| `playback_song_idx (song_id)` | Joins from matching songs to their playback events and referencing-row lookups |

The three secondary playback indexes have recorded production usage. Keep them.
Both time indexes are useful: a channel-leading index does not efficiently provide
global time ordering across all channels. For the API's same-direction timestamp
and ID sorting, a B-tree can serve ascending order through a backward scan; a
separate ascending index is unnecessary.

## Index priorities as data grows

### First: recent songs and channel current songs

Implemented definitions, applied concurrently on production:

```sql
CREATE INDEX CONCURRENTLY song_last_played_id_idx
    ON song (last_played DESC, id DESC);

CREATE INDEX CONCURRENTLY song_channel_last_played_id_idx
    ON song (channel, last_played DESC, id DESC);
```

The first index matches the default records page, recent endpoint, and latest-500
dashboard query. The second matches current-song lookup and channel-filtered
records ordered by last played. Its channel prefix also supports channel-only
selection, although returning a large fraction of the table may still favor a
sequential scan. It does not directly satisfy `ORDER BY channel, id` because
`last_played` intervenes.

Keep ID in these definitions to match stable records pagination. The existing
unique index has channel last and cannot efficiently replace a channel-leading
index for channel-only filtering. These two candidate indexes serve different
global and channel-scoped ordering needs.

These two indexes have now been implemented and their production plans verified.
Their benefit has not been benchmarked on a larger dataset in this review, so
this is a preventive workload-based change rather than a response to slow queries.

### Next, if frequently used: most-played ordering

```sql
CREATE INDEX CONCURRENTLY song_play_count_id_idx
    ON song (play_count DESC, id DESC);
```

This matches records sorting by play count and supports the top-20 most-played
endpoint's leading ordering. A channel-leading variant should be considered
only if channel-specific rankings are a common measured workload.

Every replay updates both last played and play count. Indexing these columns adds
maintenance to those updates and prevents HOT updates when indexed values change.
That is a reason to prioritize useful indexes, not to index every sortable column.
See [PostgreSQL's HOT update requirements](https://www.postgresql.org/docs/17/storage-hot.html).

### Later, if search becomes expensive: substring search

The actual search predicate is an OR across `lower(title)`, `lower(artist)`, and
`lower(album)`, each using `LIKE '%literal%'`. Ordinary B-tree indexes on those
columns, including B-trees on their lowercase expressions, do not solve this
leading-wildcard search.

If search becomes a bottleneck, evaluate `pg_trgm` and expression GIN indexes:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX CONCURRENTLY song_title_trgm_idx
    ON song USING gin (lower(title) gin_trgm_ops);
CREATE INDEX CONCURRENTLY song_artist_trgm_idx
    ON song USING gin (lower(artist) gin_trgm_ops);
CREATE INDEX CONCURRENTLY song_album_trgm_idx
    ON song USING gin (lower(album) gin_trgm_ops);
```

The extension is currently absent. This SQL is a future option, not an applied
change. The expressions match the current predicates and can allow bitmap OR
plans. Search still needs ordering and possibly a playback join. Short or
otherwise nonselective patterns may benefit little; patterns without extractable
trigrams can require a full index scan. Test representative searches before
adding all three indexes. A combined text expression would require corresponding
query changes and care to preserve the existing three-field search semantics.

### Defer indexes for occasional sort fields and backfills

Records also supports title, album, release year, first played, channel, and ID
sorting. Small-table sorting is inexpensive, and artist ordering already benefits
from the unique index in the sampled plans. Do not add an index for every UI
column. If a specific ordering becomes costly and common, test an index matching
that field and the correct ID tie-breaker.

Playback metadata sorting orders by a joined song field and **playback event ID**.
A song index alone does not guarantee that full order, especially after adding
channel/date filters. Recheck the complete joined query before recommending one.

The recording backfill already traverses primary-key ranges using `id > afterId`
and a limit. Partial indexes on IDs with missing ISRCs or unavailable matches are
only worth evaluating if later backfills spend substantial time skipping completed
rows. They are not needed for the current small catalogue.

## Growth limits that indexes alone will not resolve

- **Deep pagination:** records uses OFFSET, with pages up to 1,000,000. Even with
  an appropriate ordering index, skipped rows require work. For very large
  history, consider cursor pagination using `(observed_at, id)` for the default
  history ordering. This would be a deliberate API/UI change; preserve existing
  numbered-page behavior until such a change is requested.
- **Exact totals:** Spring Data `Page` can execute a separate filtered count to
  provide total elements/pages. Counts still process all matching rows, and text
  search counts can require joins. Measure page and count queries separately.
- **Dashboard aggregates:** channel/year totals inspect the full catalogue. A
  B-tree does not make exact whole-table aggregation constant-time. Existing
  30-second application caching is sufficient now; consider longer-lived summary
  computation only if aggregate costs become significant.
- **Unbounded legacy lists:** `/api/songs` and `/api/songs/channel/{channelId}`
  return all matching song entities. Indexes do not limit serialization, response
  size, or entity loading. The dashboard uses a bounded latest-500 list; preserve
  the legacy endpoints' contracts when planning any future pagination.
- **Full entity loading:** list queries currently load lyrics and other detail
  columns before mapping DTOs. If metadata grows substantially, query projections
  may reduce I/O and allocation independently of indexes. This is not urgent now.

## Follow-up and rollout

Review query frequency and total/mean execution time in the existing
`pg_stat_statements` installation as rows accumulate. Use request latency metrics
to identify user-visible regressions, then inspect the corresponding SQL plans.
As a proposed operational trigger, investigate repeated common-page database
times above 50 ms or search/count times above 100 ms; these are review targets,
not measured service-level requirements. Compare similar queries and data sizes,
and account for the application's bounded 30-second caches.

For a future index rollout on the running database, use `CREATE INDEX
CONCURRENTLY` outside a transaction block, check index validity afterward, and
verify the same query plans. Record indexes in the project's schema setup so fresh
databases receive them too. Concurrent builds reduce write blocking but still
consume resources. Avoid changing existing applied timestamp migrations.

The initial analysis used repository query review, live catalog/statistics queries,
and representative SELECT execution plans within read-only transactions with a
five-second statement timeout and one-second lock timeout. The subsequent
implementation and its backend/PostgreSQL test results are recorded above. Only
the two recency indexes were added to production; existing indexes were retained.

## References

- Query implementations: `RecordsQuery`, `RecordsService`, `SongService`,
  `SongRepository`, and `PlaybackEventRepository`; cache behavior in the records
  and song controllers.
- [PostgreSQL 17: multicolumn indexes](https://www.postgresql.org/docs/17/indexes-multicolumn.html)
- [PostgreSQL 17: indexes and ordering](https://www.postgresql.org/docs/17/indexes-ordering.html)
- [PostgreSQL 17: trigram indexes](https://www.postgresql.org/docs/17/pgtrgm.html)
- [PostgreSQL 17: LIMIT and OFFSET](https://www.postgresql.org/docs/17/queries-limit.html)
- [PostgreSQL 17: creating indexes, including concurrent builds](https://www.postgresql.org/docs/17/sql-createindex.html)
