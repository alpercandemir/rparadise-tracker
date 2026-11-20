package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.PlaybackRecord;
import com.radioparadise.tracker.dto.RecordsPage;
import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.dto.UnifiedSong;
import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import com.radioparadise.tracker.repository.SongRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordsService {
    private static final String LITERAL_TERM_CONDITION =
            "public.f_song_search_text(s.title, s.artist, s.album) "
                    + "LIKE public.f_unaccent(?) ESCAPE '\\'";
    private static final String LITERAL_OR_COMPACT_ARTIST_TERM_CONDITION =
            """
            (
                public.f_song_search_text(s.title, s.artist, s.album) LIKE public.f_unaccent(?) ESCAPE '\\'
                OR public.f_compact_text(s.artist) LIKE '%' || public.f_compact_text(?) || '%'
            )
            """;
    private static final String FUZZY_TERM_CONDITION =
            """
            (
                public.f_song_search_text(s.title, s.artist, s.album) LIKE public.f_unaccent(?) ESCAPE '\\'
                OR public.f_song_search_text(s.title, s.artist, s.album) operator(public.%>>) public.f_unaccent(?)
                OR public.f_phonetic_codes(s.title, s.artist, s.album) && public.f_query_phonetic_codes(?)
            )
            AND (
                public.f_song_search_text(s.title, s.artist, s.album) LIKE public.f_unaccent(?) ESCAPE '\\'
                OR public.strict_word_similarity(public.f_unaccent(?), public.f_song_search_text(s.title, s.artist, s.album)) >= 0.6
                OR public.f_phonetic_word_match(s.title, s.artist, s.album, ?)
            )
            """;

    private static final List<String> SEARCH_SCHEMA_STATEMENTS =
            List.of(
                    "CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public",
                    "CREATE EXTENSION IF NOT EXISTS fuzzystrmatch WITH SCHEMA public",
                    "CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public",
                    """
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
                    $func$
                    """,
                    """
                    CREATE OR REPLACE FUNCTION public.f_song_search_text(text, text, text)
                        RETURNS text
                        LANGUAGE sql
                        IMMUTABLE PARALLEL SAFE
                    AS $func$
                        SELECT public.f_unaccent(COALESCE($1, '') || ' ' || COALESCE($2, '') || ' ' || COALESCE($3, ''))
                    $func$
                    """,
                    """
                    CREATE OR REPLACE FUNCTION public.f_compact_text(text)
                        RETURNS text
                        LANGUAGE sql
                        IMMUTABLE PARALLEL SAFE
                    AS $func$
                        SELECT pg_catalog.btrim(
                            pg_catalog.regexp_replace(
                                pg_catalog.regexp_replace(
                                    public.f_unaccent($1),
                                    '[^a-z0-9\\s]+',
                                    '',
                                    'g'
                                ),
                                '\\s+',
                                ' ',
                                'g'
                            )
                        )
                    $func$
                    """,
                    """
                    CREATE OR REPLACE FUNCTION public.f_phonetic_skeleton(text)
                        RETURNS text
                        LANGUAGE sql
                        IMMUTABLE PARALLEL SAFE
                    AS $func$
                        SELECT pg_catalog.regexp_replace(
                            pg_catalog.replace(
                                pg_catalog.translate(public.f_unaccent($1), 'y', 'i'),
                                'ph',
                                'f'
                            ),
                            '(.)\\1+',
                            '\\1',
                            'g'
                        )
                    $func$
                    """,
                    """
                    CREATE OR REPLACE FUNCTION public.f_song_search_rank(text, text, text, text)
                        RETURNS integer
                        LANGUAGE sql
                        IMMUTABLE PARALLEL SAFE
                    AS $func$
                        WITH normalized AS (
                            SELECT
                                pg_catalog.btrim(public.f_unaccent($1)) AS norm_title,
                                pg_catalog.btrim(public.f_unaccent($2)) AS norm_artist,
                                pg_catalog.btrim(public.f_unaccent($3)) AS norm_album,
                                pg_catalog.btrim(pg_catalog.regexp_replace(public.f_unaccent($4), '\\s+', ' ', 'g')) AS norm_query,
                                public.f_compact_text($1) AS compact_title,
                                public.f_compact_text($2) AS compact_artist,
                                public.f_compact_text($3) AS compact_album,
                                public.f_compact_text($4) AS compact_query,
                                ARRAY(
                                    SELECT DISTINCT w
                                    FROM pg_catalog.unnest(
                                        pg_catalog.regexp_split_to_array(public.f_unaccent($2), '[^a-z0-9]+')
                                        || pg_catalog.regexp_split_to_array(public.f_compact_text($2), '\\s+')
                                    ) AS w
                                    WHERE w <> ''
                                ) AS artist_words,
                                ARRAY(
                                    SELECT DISTINCT w
                                    FROM pg_catalog.unnest(
                                        pg_catalog.regexp_split_to_array(public.f_unaccent($1), '[^a-z0-9]+')
                                        || pg_catalog.regexp_split_to_array(public.f_compact_text($1), '\\s+')
                                    ) AS w
                                    WHERE w <> ''
                                ) AS title_words,
                                ARRAY(
                                    SELECT DISTINCT w
                                    FROM pg_catalog.unnest(
                                        pg_catalog.regexp_split_to_array(public.f_unaccent($3), '[^a-z0-9]+')
                                        || pg_catalog.regexp_split_to_array(public.f_compact_text($3), '\\s+')
                                    ) AS w
                                    WHERE w <> ''
                                ) AS album_words
                        ),
                        terms AS (
                            SELECT
                                raw_term,
                                COALESCE(NULLIF(public.f_compact_text(raw_term), ''), raw_term) AS term
                            FROM normalized n,
                                 LATERAL pg_catalog.regexp_split_to_table(n.norm_query, '\\s+') AS raw_term
                            WHERE raw_term <> ''
                        ),
                        term_scores AS (
                            SELECT
                                CASE
                                    WHEN t.term = ANY(n.artist_words) THEN 1
                                    WHEN t.term = ANY(n.title_words) THEN 2
                                    WHEN t.term = ANY(n.album_words) THEN 3
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.artist_words) AS w
                                        WHERE pg_catalog.strpos(w, t.term) = 1
                                    ) THEN 4
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.title_words) AS w
                                        WHERE pg_catalog.strpos(w, t.term) = 1
                                    ) THEN 5
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.album_words) AS w
                                        WHERE pg_catalog.strpos(w, t.term) = 1
                                    ) THEN 6
                                    WHEN pg_catalog.strpos(n.norm_artist, t.raw_term) > 0
                                        OR pg_catalog.strpos(n.compact_artist, t.term) > 0
                                        OR pg_catalog.strpos(n.norm_title, t.raw_term) > 0
                                        OR pg_catalog.strpos(n.compact_title, t.term) > 0 THEN 7
                                    WHEN pg_catalog.strpos(n.norm_album, t.raw_term) > 0
                                        OR pg_catalog.strpos(n.compact_album, t.term) > 0 THEN 8
                                    ELSE 9
                                END AS literal_tier,
                                CASE
                                    WHEN t.term = ANY(n.artist_words) OR t.term = ANY(n.title_words) THEN 0
                                    WHEN t.term = ANY(n.album_words) THEN 10
                                    WHEN pg_catalog.strpos(n.norm_artist, t.raw_term) > 0
                                        OR pg_catalog.strpos(n.compact_artist, t.term) > 0
                                        OR pg_catalog.strpos(n.norm_title, t.raw_term) > 0
                                        OR pg_catalog.strpos(n.compact_title, t.term) > 0
                                        OR pg_catalog.strpos(n.norm_album, t.raw_term) > 0
                                        OR pg_catalog.strpos(n.compact_album, t.term) > 0 THEN 20
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.artist_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.f_phonetic_skeleton(w) = public.f_phonetic_skeleton(t.term)
                                    ) THEN 25
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.title_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.f_phonetic_skeleton(w) = public.f_phonetic_skeleton(t.term)
                                    ) THEN 35
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.album_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.f_phonetic_skeleton(w) = public.f_phonetic_skeleton(t.term)
                                    ) THEN 45
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.artist_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.levenshtein_less_equal(w, t.term, 1) <= 1
                                    ) THEN 60 - (
                                        public.strict_word_similarity(t.term, n.norm_artist) * 15
                                    )::integer
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.title_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.levenshtein_less_equal(w, t.term, 1) <= 1
                                    ) THEN 70 - (
                                        public.strict_word_similarity(t.term, n.norm_title) * 15
                                    )::integer
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.album_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.levenshtein_less_equal(w, t.term, 1) <= 1
                                    ) THEN 85 - (
                                        public.strict_word_similarity(t.term, n.norm_album) * 15
                                    )::integer
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.artist_words || n.title_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.levenshtein_less_equal(w, t.term, 2) <= 2
                                    ) THEN 115 - (
                                        public.strict_word_similarity(t.term, n.norm_artist || ' ' || n.norm_title) * 15
                                    )::integer
                                    WHEN EXISTS (
                                        SELECT 1 FROM pg_catalog.unnest(n.album_words) AS w
                                        WHERE pg_catalog.length(w) >= 3
                                          AND public.levenshtein_less_equal(w, t.term, 2) <= 2
                                    ) THEN 135 - (
                                        public.strict_word_similarity(t.term, n.norm_album) * 15
                                    )::integer
                                    ELSE 170 - (
                                        public.strict_word_similarity(t.term, public.f_song_search_text($1, $2, $3)) * 20
                                    )::integer
                                END AS fuzzy_penalty
                            FROM normalized n, terms t
                        )
                        SELECT
                            CASE
                                WHEN n.norm_query = '' THEN 0
                                WHEN n.norm_artist = n.norm_query
                                    OR (n.compact_query <> '' AND n.compact_artist = n.compact_query)
                                    THEN 10
                                WHEN n.norm_title = n.norm_query
                                    OR (n.compact_query <> '' AND n.compact_title = n.compact_query)
                                    THEN 15
                                WHEN n.compact_query <> ''
                                    AND (
                                        n.compact_artist || ' ' || n.compact_title = n.compact_query
                                        OR n.compact_title || ' ' || n.compact_artist = n.compact_query
                                    )
                                    THEN 20
                                WHEN n.norm_album = n.norm_query
                                    OR (n.compact_query <> '' AND n.compact_album = n.compact_query)
                                    THEN 30
                                WHEN n.compact_query <> ''
                                    AND pg_catalog.strpos(n.compact_artist, n.compact_query || ' ') = 1
                                    THEN 40
                                WHEN n.compact_query <> ''
                                    AND pg_catalog.strpos(n.compact_title, n.compact_query || ' ') = 1
                                    THEN 45
                                WHEN n.compact_query <> ''
                                    AND pg_catalog.strpos(' ' || n.compact_artist || ' ', ' ' || n.compact_query || ' ') > 0
                                    THEN 50
                                WHEN n.compact_query <> ''
                                    AND pg_catalog.strpos(' ' || n.compact_title || ' ', ' ' || n.compact_query || ' ') > 0
                                    THEN 55
                                WHEN COALESCE((SELECT pg_catalog.max(literal_tier) FROM term_scores), 9) <= 8
                                    THEN 100
                                        + (SELECT pg_catalog.max(literal_tier) * 30 + (pg_catalog.avg(literal_tier) * 10)::integer FROM term_scores)
                                ELSE 1000
                                    + COALESCE((SELECT pg_catalog.max(fuzzy_penalty) * 10 + pg_catalog.avg(fuzzy_penalty)::integer FROM term_scores), 0)
                            END
                        FROM normalized n
                    $func$
                    """,
                    """
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
                    $func$
                    """,
                    """
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
                    $func$
                    """,
                    """
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
                    $func$
                    """,
                    """
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
                    $func$
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS song_search_trgm_idx
                        ON song USING gin (public.f_song_search_text(title, artist, album) public.gin_trgm_ops)
                    """,
                    """
                    CREATE INDEX IF NOT EXISTS song_search_phonetic_idx
                        ON song USING gin (public.f_phonetic_codes(title, artist, album))
                    """);

    private final SongRepository songRepository;
    private final PlaybackEventRepository playbackEventRepository;
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void initializeSearchSchema() {
        for (String statement : SEARCH_SCHEMA_STATEMENTS) {
            jdbcTemplate.execute(statement);
        }
    }

    public RecordsPage<TrackRecord> tracks(RecordsQuery query) {
        var literalPage = loadTracks(query, resolveLiteralSongIds(query));
        if (query.search().isEmpty() || literalPage.totalElements() > 0) {
            return literalPage;
        }
        return loadTracks(query, resolveFuzzySongIds(query));
    }

    public RecordsPage<UnifiedSong> library(RecordsQuery query) {
        var literalPage = loadLibrary(query, resolveLiteralSongIds(query));
        if (query.search().isEmpty() || literalPage.totalElements() > 0) {
            return literalPage;
        }
        return loadLibrary(query, resolveFuzzySongIds(query));
    }

    private RecordsPage<UnifiedSong> loadLibrary(RecordsQuery query, List<Long> matchingSongIds) {
        if (matchingSongIds != null && matchingSongIds.isEmpty()) {
            return new RecordsPage<>(List.of(), query.page(), query.size(), 0, 0, null);
        }
        String filter = matchingSongIds == null ? "" : " WHERE s.id IN (:ids)";
        // Group the full archive before pagination. The oldest row supplies a stable detail ID.
        String grouped = """
                SELECT min(s.id) AS id, min(s.first_played) AS first_played,
                       max(s.last_played) AS last_played, sum(s.play_count) AS play_count
                FROM song s
                """ + filter + " GROUP BY s.artist, s.title, s.album";
        if (query.newDiscoveries()) {
            // Filter after grouping: a new channel appearance must not rediscover an old song.
            // Hours keep this an absolute 30-day window even across daylight-saving changes.
            grouped += " HAVING min(s.first_played) BETWEEN "
                    + "CURRENT_TIMESTAMP - INTERVAL '720 hours' AND CURRENT_TIMESTAMP";
        }
        var jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        Map<String, Object> parameters = new HashMap<>();
        if (matchingSongIds != null) {
            parameters.put("ids", matchingSongIds);
        }
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM (" + grouped + ") songs", parameters, Long.class);
        parameters.put("limit", query.size());
        parameters.put("offset", (long) query.page() * query.size());
        parameters.put("query", query.search());
        String sql = """
                SELECT s.id, s.title, s.artist, s.album, s.release_year, s.cover_url,
                       g.first_played, g.last_played, g.play_count
                FROM (
                """ + grouped + ") g JOIN song s ON s.id = g.id ORDER BY "
                + libraryOrdering(query) + " LIMIT :limit OFFSET :offset";
        var items = jdbc.query(sql, parameters, (rs, row) -> new UnifiedSong(
                rs.getLong("id"), rs.getString("title"), rs.getString("artist"),
                rs.getString("album"), rs.getString("release_year"), rs.getString("cover_url"),
                rs.getTimestamp("first_played").toInstant(),
                rs.getTimestamp("last_played").toInstant(), rs.getLong("play_count")));
        int totalPages = (int) ((total + query.size() - 1) / query.size());
        return new RecordsPage<>(items, query.page(), query.size(), total, totalPages, null);
    }

    private String libraryOrdering(RecordsQuery query) {
        if (query.sort().equals("relevance")) {
            if (query.search().isEmpty()) {
                return "g.last_played DESC, s.id DESC";
            }
            return "public.f_song_search_rank(s.title, s.artist, s.album, :query) ASC, "
                    + "g.last_played DESC, s.id DESC";
        }
        String column = switch (query.sort()) {
            case "id" -> "s.id";
            case "title" -> "s.title";
            case "artist" -> "s.artist";
            case "album" -> "s.album";
            case "year" -> "s.release_year";
            case "firstPlayed" -> "g.first_played";
            case "lastPlayed" -> "g.last_played";
            case "playCount" -> "g.play_count";
            default -> throw new IllegalArgumentException("Unsupported sort field: " + query.sort());
        };
        String direction = query.direction().name();
        return column + " " + direction + (column.equals("s.id") ? "" : ", s.id " + direction);
    }

    private RecordsPage<TrackRecord> loadTracks(RecordsQuery query, List<Long> matchingSongIds) {
        if (matchingSongIds != null && matchingSongIds.isEmpty()) {
            return RecordsPage.from(Page.empty(query.pageable(false)), null);
        }
        Specification<Song> filter =
                (root, criteria, cb) -> {
                    List<Predicate> predicates =
                            songFilters(query, matchingSongIds, root, root.get("channel"), cb);
                    return cb.and(predicates.toArray(Predicate[]::new));
                };
        return RecordsPage.from(
                songRepository.findAll(filter, query.pageable(false)).map(TrackRecord::from), null);
    }

    public RecordsPage<PlaybackRecord> plays(RecordsQuery query) {
        var historyStartedAt =
                playbackEventRepository
                        .findFirstByOrderByObservedAtAscIdAsc()
                        .map(PlaybackEvent::getObservedAt)
                        .orElse(null);
        var literalPage = loadPlays(query, resolveLiteralSongIds(query), historyStartedAt);
        if (query.search().isEmpty() || literalPage.totalElements() > 0) {
            return literalPage;
        }
        return loadPlays(query, resolveFuzzySongIds(query), historyStartedAt);
    }

    private RecordsPage<PlaybackRecord> loadPlays(
            RecordsQuery query, List<Long> matchingSongIds, Instant historyStartedAt) {
        if (matchingSongIds != null && matchingSongIds.isEmpty()) {
            return RecordsPage.from(Page.empty(query.pageable(true)), historyStartedAt);
        }
        Specification<PlaybackEvent> filter =
                (root, criteria, cb) -> {
                    List<Predicate> predicates =
                            songFilters(
                                    query,
                                    matchingSongIds,
                                    root.get("song"),
                                    root.get("channel"),
                                    cb);
                    if (query.from() != null) {
                        predicates.add(
                                cb.greaterThanOrEqualTo(
                                        root.get("observedAt"),
                                        query.from().atStartOfDay(query.timeZone()).toInstant()));
                    }
                    if (query.to() != null) {
                        predicates.add(
                                cb.lessThan(
                                        root.get("observedAt"),
                                        query.to()
                                                .plusDays(1)
                                                .atStartOfDay(query.timeZone())
                                                .toInstant()));
                    }
                    return cb.and(predicates.toArray(Predicate[]::new));
                };
        var page =
                playbackEventRepository
                        .findAll(filter, query.pageable(true))
                        .map(PlaybackRecord::from);
        return RecordsPage.from(page, historyStartedAt);
    }

    private List<Predicate> songFilters(
            RecordsQuery query,
            List<Long> matchingSongIds,
            Path<?> song,
            Path<String> channel,
            CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        if (!query.channel().isEmpty()) {
            predicates.add(cb.equal(channel, query.channel()));
        }
        if (matchingSongIds != null) {
            predicates.add(song.get("id").in(matchingSongIds));
        }
        return predicates;
    }

    private List<Long> resolveLiteralSongIds(RecordsQuery query) {
        if (query.search().isEmpty()) {
            return null;
        }
        List<String> conditions = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        for (String term : query.search().split("\\s+")) {
            appendLiteralTermCondition(term, conditions, arguments);
        }
        return findSongIds(conditions, arguments);
    }

    private void appendLiteralTermCondition(
            String term, List<String> conditions, List<Object> arguments) {
        String pattern = likePattern(term);
        if (isCompactCandidate(term)) {
            conditions.add(LITERAL_OR_COMPACT_ARTIST_TERM_CONDITION);
            arguments.add(pattern);
            arguments.add(term);
            return;
        }
        conditions.add(LITERAL_TERM_CONDITION);
        arguments.add(pattern);
    }

    private List<Long> resolveFuzzySongIds(RecordsQuery query) {
        List<String> conditions = new ArrayList<>();
        List<Object> arguments = new ArrayList<>();
        for (String term : query.search().split("\\s+")) {
            appendTermCondition(term, conditions, arguments);
        }
        return findSongIds(conditions, arguments);
    }

    private List<Long> findSongIds(List<String> conditions, List<Object> arguments) {
        String sql = "SELECT s.id FROM song s WHERE " + String.join(" AND ", conditions);
        return jdbcTemplate.execute((Connection connection) -> {
            try (Statement statement = connection.createStatement()) {
                // Keep one-letter typos as index candidates; the term condition checks relevance.
                statement.execute("SET LOCAL pg_trgm.strict_word_similarity_threshold = 0.24");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < arguments.size(); i++) {
                    statement.setObject(i + 1, arguments.get(i));
                }
                List<Long> ids = new ArrayList<>();
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
                return ids;
            }
        });
    }

    private void appendTermCondition(
            String term, List<String> conditions, List<Object> arguments) {
        String pattern = likePattern(term);
        if (!isFuzzyCandidate(term)) {
            conditions.add(LITERAL_TERM_CONDITION);
            arguments.add(pattern);
            return;
        }
        conditions.add(FUZZY_TERM_CONDITION);
        arguments.add(pattern);
        arguments.add(term);
        arguments.add(term);
        arguments.add(pattern);
        arguments.add(term);
        arguments.add(term);
    }

    private String likePattern(String term) {
        String literal =
                term.toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_");
        return "%" + literal + "%";
    }

    private boolean isFuzzyCandidate(String term) {
        return term.length() >= 3 && !hasLikeSpecialCharacters(term);
    }

    private boolean isCompactCandidate(String term) {
        if (hasLikeSpecialCharacters(term)) {
            return false;
        }
        int alphanumericCount = 0;
        for (int i = 0; i < term.length(); i++) {
            if (Character.isLetterOrDigit(term.charAt(i))) {
                alphanumericCount++;
            }
        }
        return alphanumericCount >= 2;
    }

    private boolean hasLikeSpecialCharacters(String term) {
        return term.contains("%") || term.contains("_") || term.contains("\\");
    }
}
