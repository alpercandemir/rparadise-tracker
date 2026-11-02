-- Run using psql -X -v ON_ERROR_STOP=1 against the application database.
-- Keep autocommit enabled: concurrent index builds cannot run in a transaction.
-- Apply before deploying the matching Song annotations to an existing database.
SET lock_timeout = '5s';
SET statement_timeout = '60s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS song_last_played_id_idx
    ON song (last_played DESC, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS song_channel_last_played_id_idx
    ON song (channel, last_played DESC, id DESC);

RESET lock_timeout;
RESET statement_timeout;
