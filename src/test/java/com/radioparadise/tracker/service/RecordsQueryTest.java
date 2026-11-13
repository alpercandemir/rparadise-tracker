package com.radioparadise.tracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Sort;

class RecordsQueryTest {
    @Test
    void discoveriesDefaultToNewestFirstAndRemainSeparateCacheKeys() {
        RecordsQuery discoveries = RecordsQuery.parseLibrary(Map.of("discovery", "30d"));

        assertThat(discoveries.newDiscoveries()).isTrue();
        assertThat(discoveries.pageable(false).getSort())
                .containsExactly(Sort.Order.desc("firstPlayed"), Sort.Order.desc("id"));
        assertThat(discoveries).isNotEqualTo(RecordsQuery.parseLibrary(Map.of("sort", "firstPlayed")));
        assertThat(RecordsQuery.parseLibrary(Map.of("discovery", "30d", "sort", "playCount")).sort())
                .isEqualTo("playCount");
        assertThat(RecordsQuery.parse(Map.of("discovery", "30d"), false).newDiscoveries()).isFalse();
        assertThatThrownBy(() -> RecordsQuery.parseLibrary(Map.of("discovery", "7d")))
                .hasMessage("discovery must be 30d");
    }

    @Test
    void libraryUsesTrackPaginationAndRejectsChannelSpecificQueries() {
        RecordsQuery query = RecordsQuery.parseLibrary(Map.of("sort", "playCount", "q", " Muse "));

        assertThat(query.search()).isEqualTo("Muse");
        assertThat(query.pageable(false).getSort())
                .containsExactly(Sort.Order.desc("playCount"), Sort.Order.desc("id"));
        assertThatThrownBy(() -> RecordsQuery.parseLibrary(Map.of("channel", "1")))
                .hasMessage("The song library combines all channels");
        assertThatThrownBy(() -> RecordsQuery.parseLibrary(Map.of("sort", "channel")))
                .hasMessage("Unsupported sort field: channel");
    }

    @Test
    void defaultsKeepTrackAndPlaybackOrderingStable() {
        RecordsQuery tracks = RecordsQuery.parse(Map.of(), false);
        RecordsQuery plays = RecordsQuery.parse(Map.of(), true);

        assertThat(tracks.page()).isZero();
        assertThat(tracks.size()).isEqualTo(25);
        assertThat(tracks.timeZone()).isEqualTo(ZoneId.of("Europe/Istanbul"));
        assertThat(tracks.search()).isEmpty();
        assertThat(tracks.channel()).isEmpty();
        assertThat(tracks.pageable(false).getSort())
                .containsExactly(Sort.Order.desc("lastPlayed"), Sort.Order.desc("id"));
        assertThat(plays.pageable(true).getSort())
                .containsExactly(Sort.Order.desc("observedAt"), Sort.Order.desc("id"));
    }

    @ParameterizedTest
    @CsvSource({
        "title,song.title",
        "artist,song.artist",
        "album,song.album",
        "year,song.year",
        "channel,channel",
        "playedAt,observedAt"
    })
    void playbackSortsUseTheCorrectPropertyAndAnIdTieBreaker(String field, String property) {
        RecordsQuery query = RecordsQuery.parse(Map.of("sort", field, "direction", "asc"), true);

        assertThat(query.pageable(true).getSort())
                .containsExactly(Sort.Order.asc(property), Sort.Order.asc("id"));
    }

    @Test
    void sortingByIdDoesNotAddADuplicateTieBreaker() {
        RecordsQuery query = RecordsQuery.parse(Map.of("sort", "id"), true);

        assertThat(query.pageable(true).getSort()).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    void parsesFiltersAndTheBrowserTimeZoneWithoutChangingSearchContent() {
        RecordsQuery query =
                RecordsQuery.parse(
                        Map.of(
                                "page",
                                "2",
                                "size",
                                "100",
                                "q",
                                "  Artist_%  ",
                                "channel",
                                "2",
                                "from",
                                "2026-11-01",
                                "to",
                                "2026-11-01",
                                "timeZone",
                                "America/New_York"),
                        true);

        assertThat(query.pageable(true).getPageNumber()).isEqualTo(2);
        assertThat(query.pageable(true).getPageSize()).isEqualTo(100);
        assertThat(query.search()).isEqualTo("Artist_%");
        assertThat(query.channel()).isEqualTo("2");
        assertThat(query.from()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(query.to()).isEqualTo(query.from());
        assertThat(query.timeZone()).isEqualTo(ZoneId.of("America/New_York"));
    }

    @Test
    void trackQueriesContinueToIgnorePlaybackDateParameters() {
        RecordsQuery query = RecordsQuery.parse(Map.of("from", "invalid", "to", "invalid"), false);

        assertThat(query.from()).isNull();
        assertThat(query.to()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "page,-1,page must be between 0 and 1000000",
        "page,1000001,page must be between 0 and 1000000",
        "page,abc,page must be an integer",
        "size,abc,size must be an integer",
        "size,20,'size must be 25, 50, or 100'",
        "sort,lyrics,Unsupported sort field: lyrics",
        "direction,ASC,direction must be asc or desc",
        "channel,3,Unsupported channel id: 3",
        "timeZone,Not/AZone,timeZone must be a valid timezone such as UTC or America/New_York"
    })
    void rejectsInvalidParametersWithTheExistingMessages(
            String name, String value, String message) {
        assertThatThrownBy(() -> RecordsQuery.parse(Map.of(name, value), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @ParameterizedTest
    @CsvSource({"false,playedAt", "true,lastPlayed", "true,playCount"})
    void rejectsSortFieldsFromTheOtherRecordType(boolean history, String sort) {
        assertThatThrownBy(() -> RecordsQuery.parse(Map.of("sort", sort), history))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported sort field: " + sort);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-02-30,Dates must be valid YYYY-MM-DD values",
        "1899-12-31,Date year must be between 1900 and 9998",
        "9999-01-01,Date year must be between 1900 and 9998"
    })
    void rejectsInvalidPlaybackDates(String date, String message) {
        assertThatThrownBy(() -> RecordsQuery.parse(Map.of("from", date), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    @Test
    void rejectsReversedDateRanges() {
        assertThatThrownBy(
                        () ->
                                RecordsQuery.parse(
                                        Map.of("from", "2026-11-02", "to", "2026-11-01"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("from must not be after to");
    }

    @Test
    void rejectsOversizedSearches() {
        assertThatThrownBy(() -> RecordsQuery.parse(Map.of("q", "x".repeat(201)), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("q must be at most 200 characters");
    }
}
