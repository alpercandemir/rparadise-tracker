-- Public RP broadcasts are distinct from this application's observed listening history.
-- A source event reference may have multiple timestamp or metadata variants.
BEGIN;
CREATE SCHEMA IF NOT EXISTS rp_history;

CREATE TABLE IF NOT EXISTS rp_history.source_play (
    source_key text PRIMARY KEY CHECK (source_key ~ '^[0-9a-f]{64}$'),
    channel text NOT NULL CHECK (channel IN ('0', '1', '2')),
    event_id text NOT NULL,
    rp_song_id text NOT NULL,
    broadcast_at timestamptz NOT NULL,
    source_record jsonb NOT NULL,
    metadata_issues jsonb NOT NULL CHECK (jsonb_typeof(metadata_issues) = 'array'),
    first_file_sha256 text NOT NULL,
    first_file_line integer NOT NULL CHECK (first_file_line > 0),
    imported_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS source_play_channel_time_idx
    ON rp_history.source_play (channel, broadcast_at DESC, source_key);
CREATE INDEX IF NOT EXISTS source_play_event_idx
    ON rp_history.source_play (channel, event_id);
CREATE INDEX IF NOT EXISTS source_play_song_time_idx
    ON rp_history.source_play (rp_song_id, channel, broadcast_at DESC);

CREATE TABLE IF NOT EXISTS rp_history.rejected_row (
    file_sha256 text NOT NULL,
    file_line integer NOT NULL CHECK (file_line > 0),
    raw_line text NOT NULL,
    reason text NOT NULL,
    PRIMARY KEY (file_sha256, file_line)
);

-- This ledger commits in the same transaction as the file's accepted/rejected rows.
CREATE TABLE IF NOT EXISTS rp_history.import_file (
    file_sha256 text PRIMARY KEY,
    relative_path text NOT NULL,
    source_rows integer NOT NULL,
    inserted_rows integer NOT NULL,
    duplicate_rows integer NOT NULL,
    rejected_rows integer NOT NULL,
    completed_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_rows = inserted_rows + duplicate_rows + rejected_rows)
);
COMMIT;
