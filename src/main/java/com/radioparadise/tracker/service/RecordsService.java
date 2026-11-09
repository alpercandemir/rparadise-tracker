package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.PlaybackRecord;
import com.radioparadise.tracker.dto.RecordsPage;
import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import com.radioparadise.tracker.repository.SongRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecordsService {
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
                              AND ARRAY[
                                  public.dmetaphone(word),
                                  public.dmetaphone_alt(word),
                                  public.soundex(word)
                              ] && public.f_query_phonetic_codes($4)
                              AND public.levenshtein(word, public.f_unaccent($4)) <= CASE
                                  WHEN pg_catalog.length(public.f_unaccent($4)) >= 5 THEN 2
                                  ELSE 1
                              END
                        )
                    $func$
                    """,
                    """
                    CREATE OR REPLACE FUNCTION public.f_song_fuzzy_match(text, text, text, text)
                        RETURNS boolean
                        LANGUAGE sql
                        IMMUTABLE PARALLEL SAFE
                    AS $func$
                        SELECT public.strict_word_similarity(
                                public.f_unaccent($4),
                                public.f_song_search_text($1, $2, $3)
                            ) >= 0.40
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
        Specification<Song> filter =
                (root, criteria, cb) -> {
                    List<Predicate> predicates = songFilters(query, root, root.get("channel"), cb);
                    return cb.and(predicates.toArray(Predicate[]::new));
                };
        return RecordsPage.from(
                songRepository.findAll(filter, query.pageable(false)).map(TrackRecord::from), null);
    }

    public RecordsPage<PlaybackRecord> plays(RecordsQuery query) {
        Specification<PlaybackEvent> filter =
                (root, criteria, cb) -> {
                    List<Predicate> predicates =
                            songFilters(query, root.get("song"), root.get("channel"), cb);
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
        var historyStartedAt =
                playbackEventRepository
                        .findFirstByOrderByObservedAtAscIdAsc()
                        .map(PlaybackEvent::getObservedAt)
                        .orElse(null);
        return RecordsPage.from(page, historyStartedAt);
    }

    private List<Predicate> songFilters(
            RecordsQuery query, Path<?> song, Path<String> channel, CriteriaBuilder cb) {
        List<Predicate> predicates = new ArrayList<>();
        if (!query.channel().isEmpty()) {
            predicates.add(cb.equal(channel, query.channel()));
        }
        if (!query.search().isEmpty()) {
            for (String term : query.search().split("\\s+")) {
                predicates.add(matchesSongTerm(song, term, cb));
            }
        }
        return predicates;
    }

    private Predicate matchesSongTerm(Path<?> song, String term, CriteriaBuilder cb) {
        Expression<String> searchText =
                cb.function(
                        "f_song_search_text",
                        String.class,
                        song.get("title"),
                        song.get("artist"),
                        song.get("album"));
        Expression<String> pattern =
                cb.function("f_unaccent", String.class, cb.literal(likePattern(term)));
        Predicate substringMatch = cb.like(searchText, pattern, '\\');
        if (!isFuzzyCandidate(term)) {
            return substringMatch;
        }
        Predicate fuzzyMatch =
                cb.isTrue(
                        cb.function(
                                "f_song_fuzzy_match",
                                Boolean.class,
                                song.get("title"),
                                song.get("artist"),
                                song.get("album"),
                                cb.literal(term)));
        return cb.or(substringMatch, fuzzyMatch);
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
        return term.length() >= 3 && !term.contains("%") && !term.contains("_");
    }
}
