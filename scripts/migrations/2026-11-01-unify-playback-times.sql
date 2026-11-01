-- Back up the database and STOP the old collector before running this script.
-- Run using psql -v ON_ERROR_STOP=1, then start the Instant-based application.
-- Existing playback events are authoritative; no events or song IDs are deleted.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
LOCK TABLE song, playback_event IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    column_name text;
    column_type oid;
BEGIN
    IF EXISTS (SELECT 1 FROM song s WHERE NOT EXISTS (
        SELECT 1 FROM playback_event p WHERE p.song_id = s.id
    )) THEN
        RAISE EXCEPTION 'Migration aborted: unrelated song without playback history';
    END IF;

    FOREACH column_name IN ARRAY ARRAY['first_played', 'last_played'] LOOP
        SELECT atttypid INTO column_type FROM pg_attribute
        WHERE attrelid = 'song'::regclass AND attname = column_name AND NOT attisdropped;
        IF column_type = 'timestamp without time zone'::regtype THEN
            EXECUTE format('ALTER TABLE song ALTER COLUMN %I TYPE timestamptz USING %I AT TIME ZONE %L',
                           column_name, column_name, 'Europe/Istanbul');
        ELSIF column_type IS DISTINCT FROM 'timestamp with time zone'::regtype::oid THEN
            RAISE EXCEPTION 'Unexpected timestamp type for song.%', column_name;
        END IF;
    END LOOP;
END $$;

UPDATE song s
SET first_played = history.first_played,
    last_played = history.last_played,
    play_count = history.play_count
FROM (
    SELECT song_id, min(observed_at) AS first_played,
           max(observed_at) AS last_played, count(*)::integer AS play_count
    FROM playback_event GROUP BY song_id
) history
WHERE s.id = history.song_id;

COMMIT;
