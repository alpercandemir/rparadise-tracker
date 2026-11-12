-- Offline maintenance only. Stop all application/maintenance writers before running.
-- Existing observations and song metadata stay authoritative; the merge is atomic and retryable.
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT pg_advisory_xact_lock(127934, 2);
LOCK TABLE public.song, public.playback_event IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE rp_history.source_play IN SHARE MODE;

ALTER TABLE public.playback_event ADD COLUMN IF NOT EXISTS rp_source_key varchar(64);
CREATE UNIQUE INDEX IF NOT EXISTS playback_rp_source_key_idx
    ON public.playback_event (rp_source_key) WHERE rp_source_key IS NOT NULL;
CREATE TABLE IF NOT EXISTS rp_history.playback_merge (
    source_key text PRIMARY KEY REFERENCES rp_history.source_play(source_key),
    outcome text NOT NULL CHECK (outcome IN (
        'imported', 'matched_observation', 'invalid_metadata', 'ambiguous_event',
        'ambiguous_broadcast_time', 'ambiguous_observation')),
    playback_event_id bigint REFERENCES public.playback_event(id),
    merged_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((outcome IN ('imported', 'matched_observation')) = (playback_event_id IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS playback_merge_event_idx
    ON rp_history.playback_merge (playback_event_id) WHERE playback_event_id IS NOT NULL;

CREATE TEMP TABLE merge_summary (new_sources bigint, new_songs bigint) ON COMMIT DROP;
INSERT INTO merge_summary VALUES (0, 0);
SELECT NOT EXISTS (
    SELECT 1 FROM rp_history.source_play a
    LEFT JOIN rp_history.playback_merge m USING (source_key) WHERE m.source_key IS NULL
) AS already_merged \gset

\if :already_merged
\else

-- Snapshot identities, times, metadata, and checkpoints before changing statistics.
CREATE TEMP TABLE original_observations ON COMMIT DROP AS
SELECT p.id, p.song_id, p.channel, p.observed_at, s.rp_song_id, s.artist, s.title, s.album
FROM public.playback_event p JOIN public.song s ON s.id = p.song_id
WHERE p.rp_source_key IS NULL;
CREATE INDEX ON original_observations (channel, observed_at);
CREATE TEMP TABLE original_song_metadata ON COMMIT DROP AS
SELECT id, to_jsonb(s) - ARRAY['first_played', 'last_played', 'play_count'] AS metadata
FROM public.song s;
CREATE TEMP TABLE original_checkpoints ON COMMIT DROP AS
SELECT DISTINCT ON (channel) channel, id, song_id, observed_at
FROM public.playback_event ORDER BY channel, observed_at DESC, id DESC;

-- Classify against the entire archive before excluding already-merged source keys.
-- Conflicting source references/timestamps remain in staging for manual review.
CREATE TEMP TABLE merge_plan ON COMMIT DROP AS
WITH metadata AS MATERIALIZED (
    SELECT a.source_key, a.channel, a.event_id, a.broadcast_at, a.metadata_issues,
           a.source_record->'data'->>'artist' AS artist,
           a.source_record->'data'->>'title' AS title,
           a.source_record->'data'->>'album' AS album,
           a.source_record->'data'->>'year' AS release_year,
           a.source_record->'data'->>'cover' AS cover,
           a.source_record->'data'->>'cover_med' AS cover_med,
           a.source_record->'data'->>'cover_small' AS cover_small,
           CASE WHEN a.rp_song_id ~ '^[0-9]{1,18}$' THEN a.rp_song_id::bigint END AS rp_song_id,
           CASE WHEN a.source_record->'data'->>'duration' ~ '^[0-9]{1,9}$'
                THEN (a.source_record->'data'->>'duration')::bigint END AS duration_ms
    FROM rp_history.source_play a
), source AS (
    SELECT a.*,
           count(*) OVER (PARTITION BY channel, event_id) AS reference_rows,
           count(*) OVER (PARTITION BY channel, broadcast_at) AS timestamp_rows,
           lead(broadcast_at) OVER (PARTITION BY channel ORDER BY broadcast_at, source_key) AS next_at
    FROM metadata a
), classified AS (
    SELECT a.*,
           CASE WHEN a.reference_rows > 1 THEN 'ambiguous_event'
                WHEN a.timestamp_rows > 1 THEN 'ambiguous_broadcast_time'
                WHEN a.metadata_issues <> '[]'::jsonb THEN 'invalid_metadata'
                ELSE 'ready' END AS outcome
    FROM source a LEFT JOIN rp_history.playback_merge m USING (source_key)
    WHERE m.source_key IS NULL
)
SELECT *, NULL::bigint AS playback_event_id,
       least(next_at, broadcast_at + coalesce(nullif(duration_ms, 0), 1200000) * interval '1 millisecond'
             + interval '60 seconds') AS ends_at
FROM classified;
UPDATE merge_summary SET new_sources = (SELECT count(*) FROM merge_plan);
UPDATE merge_plan SET outcome = 'invalid_metadata'
WHERE outcome = 'ready' AND (
    rp_song_id IS NULL OR rp_song_id <= 0 OR
    artist IS NULL OR btrim(artist) = '' OR length(artist) > 255 OR
    title IS NULL OR btrim(title) = '' OR length(title) > 255 OR
    album IS NULL OR btrim(album) = '' OR length(album) > 255 OR length(release_year) > 255);
CREATE INDEX ON merge_plan (channel, broadcast_at);
ANALYZE merge_plan;
ANALYZE original_observations;

-- RP times and tracker observations differ. Match within a broadcast's bounded
-- interval using an RP song ID or the application's exact metadata identity.
CREATE TEMP TABLE observation_candidates ON COMMIT DROP AS
SELECT a.source_key, o.id
FROM merge_plan a
JOIN (SELECT channel, min(observed_at) AS first_at, max(observed_at) AS last_at
      FROM original_observations GROUP BY channel) bounds
  ON bounds.channel = a.channel AND a.broadcast_at >= bounds.first_at - interval '1 day'
 AND a.broadcast_at <= bounds.last_at
JOIN original_observations o ON o.channel = a.channel
 AND o.observed_at >= a.broadcast_at AND o.observed_at < a.ends_at
 AND (o.rp_song_id = a.rp_song_id OR
      (o.artist = a.artist AND o.title = a.title AND o.album = a.album))
WHERE a.outcome = 'ready';

UPDATE merge_plan a
SET outcome = CASE WHEN c.matches = 1 THEN 'matched_observation' ELSE 'ambiguous_observation' END,
    playback_event_id = CASE WHEN c.matches = 1 THEN c.id END
FROM (SELECT source_key, count(*) AS matches, min(id) AS id
      FROM observation_candidates GROUP BY source_key) c
WHERE a.source_key = c.source_key;

-- A near miss may reflect clock/polling uncertainty. Keep it for review rather
-- than adding a second count next to an existing observation of the same song.
UPDATE merge_plan a SET outcome = 'ambiguous_observation'
WHERE a.outcome = 'ready'
  AND a.broadcast_at >= (SELECT min(observed_at) FROM original_observations) - interval '1 day'
  AND a.broadcast_at <= (SELECT max(observed_at) FROM original_observations) + interval '60 seconds'
  AND EXISTS (
    SELECT 1 FROM original_observations o WHERE o.channel = a.channel
      AND o.observed_at >= a.broadcast_at - interval '60 seconds'
      AND o.observed_at < a.ends_at + interval '60 seconds'
      AND (o.rp_song_id = a.rp_song_id OR
           (o.artist = a.artist AND o.title = a.title AND o.album = a.album))
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM merge_plan a JOIN original_checkpoints c USING (channel)
               WHERE a.outcome = 'ready' AND a.broadcast_at >= c.observed_at) THEN
        RAISE EXCEPTION 'Archive would advance a live checkpoint; inspect the source cutoff first';
    END IF;
END $$;

-- Existing rows win on the application's artist/title/album/channel identity.
-- Only new songs receive metadata from their most recent eligible source row.
WITH inserted AS (
    INSERT INTO public.song
        (artist, title, album, channel, release_year, rp_song_id, duration_seconds,
         cover_url, cover_medium_url, cover_small_url, first_played, last_played, play_count)
    SELECT DISTINCT ON (artist, title, album, channel)
           artist, title, album, channel, release_year, rp_song_id,
           CASE WHEN duration_ms > 0 THEN duration_ms / 1000.0 END,
           CASE WHEN cover ~ '^https?://' THEN cover
                WHEN cover ~ '^covers/' THEN 'https://img.radioparadise.com/' || cover END,
           CASE WHEN cover_med ~ '^https?://' THEN cover_med
                WHEN cover_med ~ '^covers/' THEN 'https://img.radioparadise.com/' || cover_med END,
           CASE WHEN cover_small ~ '^https?://' THEN cover_small
                WHEN cover_small ~ '^covers/' THEN 'https://img.radioparadise.com/' || cover_small END,
           broadcast_at, broadcast_at, 0
    FROM merge_plan WHERE outcome = 'ready'
    ORDER BY artist, title, album, channel, broadcast_at DESC, source_key
    ON CONFLICT (artist, title, album, channel) DO NOTHING
    RETURNING 1
)
UPDATE merge_summary SET new_songs = (SELECT count(*) FROM inserted);

WITH inserted AS (
    INSERT INTO public.playback_event (song_id, channel, observed_at, rp_source_key)
    SELECT s.id, a.channel, a.broadcast_at, a.source_key
    FROM merge_plan a JOIN public.song s
      ON (s.artist, s.title, s.album, s.channel) = (a.artist, a.title, a.album, a.channel)
    WHERE a.outcome = 'ready'
    RETURNING id, rp_source_key
)
UPDATE merge_plan a SET outcome = 'imported', playback_event_id = i.id
FROM inserted i WHERE a.source_key = i.rp_source_key;

INSERT INTO rp_history.playback_merge (source_key, outcome, playback_event_id)
SELECT source_key, outcome, playback_event_id FROM merge_plan;

CREATE TEMP TABLE song_totals ON COMMIT DROP AS
SELECT song_id, min(observed_at) AS first_at, max(observed_at) AS last_at, count(*)::integer AS plays
FROM public.playback_event GROUP BY song_id;
UPDATE public.song s
SET first_played = t.first_at, last_played = t.last_at, play_count = t.plays
FROM song_totals t WHERE s.id = t.song_id;

DO $$
BEGIN
    IF EXISTS (
        SELECT id, song_id, channel, observed_at FROM original_observations
        EXCEPT SELECT id, song_id, channel, observed_at FROM public.playback_event
    ) THEN
        RAISE EXCEPTION 'An original observation changed';
    END IF;
    IF EXISTS (
        SELECT id, metadata FROM original_song_metadata
        EXCEPT SELECT id, to_jsonb(s) - ARRAY['first_played', 'last_played', 'play_count'] FROM public.song s
    ) THEN
        RAISE EXCEPTION 'Existing song metadata changed';
    END IF;
    IF EXISTS (
        SELECT * FROM original_checkpoints
        EXCEPT SELECT * FROM (
            SELECT DISTINCT ON (channel) channel, id, song_id, observed_at
            FROM public.playback_event ORDER BY channel, observed_at DESC, id DESC
        ) latest
    ) THEN
        RAISE EXCEPTION 'A live checkpoint changed';
    END IF;
    IF EXISTS (SELECT 1 FROM public.song s LEFT JOIN song_totals t ON t.song_id=s.id
               WHERE t.song_id IS NULL OR (s.first_played,s.last_played,s.play_count)
                   IS DISTINCT FROM (t.first_at,t.last_at,t.plays)) THEN
        RAISE EXCEPTION 'Song statistics do not agree with playback events';
    END IF;
END $$;
\endif

SELECT jsonb_build_object(
    'new_source_rows', (SELECT new_sources FROM merge_summary),
    'new_songs', (SELECT new_songs FROM merge_summary),
    'songs', (SELECT count(*) FROM public.song),
    'playback_events', (SELECT count(*) FROM public.playback_event),
    'history_started_at', (SELECT min(observed_at) FROM public.playback_event),
    'outcomes', (SELECT jsonb_object_agg(outcome, rows) FROM (
        SELECT outcome, count(*) AS rows FROM rp_history.playback_merge GROUP BY outcome) c)
);
COMMIT;
