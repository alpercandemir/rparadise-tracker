WITH totals AS (
    SELECT song_id, count(*)::integer AS plays, min(observed_at) AS first_at,
           max(observed_at) AS last_at FROM public.playback_event GROUP BY song_id
)
SELECT jsonb_build_object(
    'checked_at', now(),
    'songs', (SELECT count(*) FROM public.song),
    'plays', (SELECT count(*) FROM public.playback_event),
    'observations', (SELECT count(*) FROM public.playback_event WHERE rp_source_key IS NULL),
    'imported', (SELECT count(*) FROM public.playback_event WHERE rp_source_key IS NOT NULL),
    'song_play_count_sum', (SELECT sum(play_count) FROM public.song),
    'history_started_at', (SELECT min(observed_at) FROM public.playback_event),
    'statistics_mismatches', (SELECT count(*) FROM public.song s LEFT JOIN totals t ON t.song_id=s.id
        WHERE t.song_id IS NULL OR (s.play_count,s.first_played,s.last_played)
              IS DISTINCT FROM (t.plays,t.first_at,t.last_at)),
    'invalid_indexes', (SELECT count(*) FROM pg_index
        WHERE indrelid IN ('public.song'::regclass,'public.playback_event'::regclass)
          AND (NOT indisvalid OR NOT indisready)),
    'outcomes', (SELECT jsonb_object_agg(outcome, rows) FROM (
        SELECT outcome, count(*) AS rows FROM rp_history.playback_merge GROUP BY outcome) c)
);
