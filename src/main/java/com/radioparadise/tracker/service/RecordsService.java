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
        var jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        Map<String, Object> parameters = new HashMap<>();
        if (matchingSongIds != null) {
            parameters.put("ids", matchingSongIds);
        }
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM (" + grouped + ") songs", parameters, Long.class);
        parameters.put("limit", query.size());
        parameters.put("offset", (long) query.page() * query.size());
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
            conditions.add(LITERAL_TERM_CONDITION);
            arguments.add(likePattern(term));
        }
        return findSongIds(conditions, arguments);
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
        return term.length() >= 3
                && !term.contains("%")
                && !term.contains("_")
                && !term.contains("\\");
    }
}
