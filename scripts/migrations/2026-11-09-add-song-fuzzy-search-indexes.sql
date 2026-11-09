-- Run using psql -X -v ON_ERROR_STOP=1 against the application database.
-- Keep autocommit enabled: concurrent index builds cannot run in a transaction.
SET lock_timeout = '5s';
SET statement_timeout = '60s';

CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;

CREATE OR REPLACE FUNCTION public.f_unaccent(text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE
AS $func$
    SELECT pg_catalog.lower(
        pg_catalog.translate(
            public.unaccent('public.unaccent'::pg_catalog.regdictionary, COALESCE($1, '')),
            'ıİ',
            'ii'
        )
    )
$func$;

CREATE OR REPLACE FUNCTION public.f_song_search_text(text, text, text)
    RETURNS text
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE
AS $func$
    SELECT public.f_unaccent(COALESCE($1, '') || ' ' || COALESCE($2, '') || ' ' || COALESCE($3, ''))
$func$;

CREATE OR REPLACE FUNCTION public.f_phonetic_codes(text, text, text)
    RETURNS text[]
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE
AS $func$
    SELECT COALESCE(
        pg_catalog.array_agg(DISTINCT code) FILTER (WHERE pg_catalog.length(code) >= 2),
        ARRAY[]::text[]
    )
    FROM pg_catalog.regexp_split_to_table(public.f_song_search_text($1, $2, $3), '[^a-z0-9]+') AS word,
         LATERAL pg_catalog.unnest(ARRAY[
             public.dmetaphone(word),
             public.dmetaphone_alt(word),
             public.soundex(word)
         ]) AS code
    WHERE pg_catalog.length(word) >= 3
$func$;

CREATE OR REPLACE FUNCTION public.f_query_phonetic_codes(text)
    RETURNS text[]
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE
AS $func$
    SELECT COALESCE(
        pg_catalog.array_agg(DISTINCT code) FILTER (WHERE pg_catalog.length(code) >= 2),
        ARRAY[]::text[]
    )
    FROM pg_catalog.unnest(ARRAY[
        public.dmetaphone(public.f_unaccent($1)),
        public.dmetaphone_alt(public.f_unaccent($1)),
        public.soundex(public.f_unaccent($1))
    ]) AS code
    WHERE pg_catalog.length(public.f_unaccent($1)) >= 3
$func$;

CREATE OR REPLACE FUNCTION public.f_phonetic_word_match(text, text, text, text)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE
AS $func$
    SELECT EXISTS (
        SELECT 1
        FROM pg_catalog.regexp_split_to_table(public.f_song_search_text($1, $2, $3), '[^a-z0-9]+') AS word
        WHERE pg_catalog.length(word) >= 3
          AND pg_catalog.abs(pg_catalog.length(word) - pg_catalog.length(public.f_unaccent($4))) <= CASE
              WHEN pg_catalog.length(public.f_unaccent($4)) >= 5 THEN 2
              ELSE 1
          END
          AND (
              (
                  pg_catalog.length(public.f_unaccent($4)) >= 4
                  AND public.levenshtein_less_equal(word, public.f_unaccent($4), 1) <= 1
              )
              OR (
                  public.levenshtein_less_equal(
                      word,
                      public.f_unaccent($4),
                      CASE WHEN pg_catalog.length(public.f_unaccent($4)) >= 5 THEN 2 ELSE 1 END
                  ) <= CASE
                      WHEN pg_catalog.length(public.f_unaccent($4)) >= 5 THEN 2
                      ELSE 1
                  END
                  AND ARRAY[
                      public.dmetaphone(word),
                      public.dmetaphone_alt(word),
                      public.soundex(word)
                  ] && public.f_query_phonetic_codes($4)
              )
          )
    )
$func$;

CREATE OR REPLACE FUNCTION public.f_song_fuzzy_match(text, text, text, text)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE PARALLEL SAFE
AS $func$
    SELECT (
            public.f_song_search_text($1, $2, $3) operator(public.%>>) public.f_unaccent($4)
            AND public.strict_word_similarity(
                public.f_unaccent($4),
                public.f_song_search_text($1, $2, $3)
            ) >= 0.24
        )
        OR (
            public.f_phonetic_codes($1, $2, $3) && public.f_query_phonetic_codes($4)
            AND public.f_phonetic_word_match($1, $2, $3, $4)
        )
$func$;

CREATE INDEX CONCURRENTLY IF NOT EXISTS song_search_trgm_idx
    ON song USING gin (public.f_song_search_text(title, artist, album) public.gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS song_search_phonetic_idx
    ON song USING gin (public.f_phonetic_codes(title, artist, album));

RESET lock_timeout;
RESET statement_timeout;
