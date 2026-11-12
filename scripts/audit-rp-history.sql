-- Read-only reconciliation report. Counts are preserved source variants, not deduplicated broadcasts.
WITH channel_counts AS (
    SELECT channel, count(*) AS source_variants, min(broadcast_at) AS earliest_broadcast_at,
           max(broadcast_at) AS latest_broadcast_at,
           count(*) FILTER (WHERE metadata_issues <> '[]'::jsonb) AS rows_with_metadata_issues
    FROM rp_history.source_play GROUP BY channel
), event_variants AS (
    SELECT channel, event_id, count(*) AS variants
    FROM rp_history.source_play GROUP BY channel, event_id HAVING count(*) > 1
), issue_counts AS (
    SELECT issue, count(*) AS rows
    FROM rp_history.source_play
    CROSS JOIN LATERAL jsonb_array_elements_text(metadata_issues) AS issue
    GROUP BY issue
), checkpoints AS (
    SELECT DISTINCT ON (channel) channel, id, song_id, observed_at
    FROM public.playback_event ORDER BY channel, observed_at DESC, id DESC
)
SELECT jsonb_pretty(jsonb_build_object(
    'checked_at', now(),
    'database', current_database(),
    'completed_files', (SELECT count(*) FROM rp_history.import_file),
    'source_rows', (SELECT coalesce(sum(source_rows), 0) FROM rp_history.import_file),
    'inserted_variants', (SELECT count(*) FROM rp_history.source_play),
    'exact_duplicates', (SELECT coalesce(sum(duplicate_rows), 0) FROM rp_history.import_file),
    'rejected_rows', (SELECT count(*) FROM rp_history.rejected_row),
    'references_with_variants', (SELECT count(*) FROM event_variants),
    'extra_reference_variants', (SELECT coalesce(sum(variants - 1), 0) FROM event_variants),
    'channels', (SELECT jsonb_agg(to_jsonb(c) ORDER BY channel) FROM channel_counts c),
    'metadata_issues', (SELECT jsonb_object_agg(issue, rows) FROM issue_counts),
    'observed_songs', (SELECT count(*) FROM public.song),
    'observed_plays', (SELECT count(*) FROM public.playback_event),
    'collector_checkpoints', (SELECT jsonb_agg(to_jsonb(c) ORDER BY channel) FROM checkpoints c),
    'archive_bytes', (SELECT sum(pg_total_relation_size(c.oid))
                      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE n.nspname = 'rp_history' AND c.relkind = 'r'),
    'database_bytes', pg_database_size(current_database())
));
