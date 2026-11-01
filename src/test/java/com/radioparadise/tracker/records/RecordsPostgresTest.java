package com.radioparadise.tracker.records;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import com.radioparadise.tracker.repository.SongRepository;
import com.radioparadise.tracker.scheduler.SongTrackerScheduler;
import com.radioparadise.tracker.service.SongService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/** Run via scripts/test-records-postgres.sh; never targets the production database. */
@EnabledIfEnvironmentVariable(named = "RECORDS_DB_TESTS", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecordsPostgresTest {
    @LocalServerPort int port;
    @Autowired SongRepository songs;
    @Autowired PlaybackEventRepository plays;
    @Autowired SongService service;
    @Autowired TransactionTemplate transactions;
    @Autowired Environment environment;
    @MockitoBean SongTrackerScheduler scheduler;
    RestClient http = RestClient.create();

    @BeforeEach
    void clearTestDatabase() {
        assertThat(environment.getRequiredProperty("spring.datasource.url"))
                .as("Integration tests must only use the disposable records_test database")
                .endsWith("/records_test");
        plays.deleteAll();
        songs.deleteAll();
    }

    @Test
    void pagesAllTracksWithStableSort() {
        songs.saveAll(IntStream.range(0, 53).mapToObj(i -> song("Track %02d".formatted(i), "1")).toList());
        Map<?, ?> page = get("/api/records/tracks?page=1&size=25&sort=title&direction=asc");
        assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(53);
        assertThat(page.get("totalPages")).isEqualTo(3);
        assertThat(page.get("page")).isEqualTo(1);
        assertThat(items(page)).hasSize(25);
        assertThat(items(page).getFirst().get("title")).isEqualTo("Track 25");
        assertThat(items(page).getLast().get("title")).isEqualTo("Track 49");
        assertThat(items(get("/api/records/tracks?page=99"))).isEmpty();
        assertThat(items(get("/api/records/tracks?sort=title&direction=desc")).getFirst().get("title"))
                .isEqualTo("Track 52");
    }

    @Test
    void equalSortValuesHaveNoOverlappingPages() {
        songs.saveAll(IntStream.range(0, 30).mapToObj(i -> {
            Song song = song("Same title", "0");
            song.setArtist("Artist " + i);
            return song;
        }).toList());
        List<Object> first = items(get("/api/records/tracks?sort=title&direction=asc"))
                .stream().map(row -> row.get("id")).toList();
        List<Object> second = items(get("/api/records/tracks?page=1&sort=title&direction=asc"))
                .stream().map(row -> row.get("id")).toList();
        assertThat(first).hasSize(25).doesNotContainAnyElementsOf(second);
        assertThat(second).hasSize(5);
    }

    @Test
    void searchAndChannelFilterCombineAndLikeWildcardsAreLiteral() {
        songs.saveAll(List.of(song("100% Pure", "1"), song("100 Percent", "1"), song("100% Pure", "2"),
                song("under_score", "1"), song("underXscore", "1")));
        List<Map<String, Object>> rows = items(get("/api/records/tracks?q=100%25&channel=1"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("title")).isEqualTo("100% Pure");
        assertThat(rows.getFirst().get("channel")).isEqualTo("1");
        assertThat(items(get("/api/records/tracks?q=under_score"))).hasSize(1);
        assertThat(items(get("/api/records/tracks?q=ALBUM&channel=2"))).hasSize(1);
    }

    @Test
    void historyDatesUseInclusiveIstanbulDaysAndExposeGlobalStartTime() {
        Song song = songs.save(song("Dates", "1"));
        for (String timestamp : List.of("2026-10-31T20:59:59Z", "2026-10-31T21:00:00Z",
                "2026-11-01T20:59:59Z", "2026-11-01T21:00:00Z")) {
            plays.save(PlaybackEvent.builder().song(song).channel("1").observedAt(Instant.parse(timestamp)).build());
        }
        Map<?, ?> page = get("/api/records/plays?from=2026-11-01&to=2026-11-01&channel=1&q=Dates");
        assertThat(items(page)).hasSize(2);
        assertThat(items(page).getFirst().get("playedAt")).isEqualTo("2026-11-01T20:59:59Z");
        assertThat(page.get("historyStartedAt")).isEqualTo("2026-10-31T20:59:59Z");
        assertThat(items(get("/api/records/tracks")).getFirst().get("firstPlayed"))
                .isEqualTo("2026-11-01T09:00:00Z");
        assertThat(items(get("/api/records/plays?sort=artist&direction=asc"))).hasSize(4);
    }

    @ParameterizedTest
    @CsvSource({
            "America/New_York,2026-03-08,2026-03-08T05:00:00Z,2026-03-09T04:00:00Z",
            "America/New_York,2026-11-01,2026-11-01T04:00:00Z,2026-11-02T05:00:00Z",
            "Asia/Tokyo,2026-11-01,2026-10-31T15:00:00Z,2026-11-01T15:00:00Z",
            "UTC,2026-11-01,2026-11-01T00:00:00Z,2026-11-02T00:00:00Z"
    })
    void filtersCalendarDaysInTheClientTimezoneIncludingDaylightSaving(String zone, String day,
                                                                       String start, String end) {
        Song song = songs.save(song("Zone boundaries", "1"));
        Instant first = Instant.parse(start);
        Instant last = Instant.parse(end);
        for (Instant time : List.of(first.minusSeconds(1), first, last.minusSeconds(1), last)) {
            plays.save(PlaybackEvent.builder().song(song).channel("1").observedAt(time).build());
        }
        Map<?, ?> page = get("/api/records/plays?from=" + day + "&to=" + day + "&timeZone=" + zone);
        assertThat(items(page)).extracting(row -> row.get("playedAt"))
                .containsExactly(last.minusSeconds(1).toString(), first.toString());
    }

    @Test
    void dashboardEndpointsAlsoReturnUnambiguousUtcTimestamps() {
        songs.save(song("UTC dashboard", "1"));
        for (String path : List.of("/api/songs", "/api/songs/recent", "/api/songs/most-played", "/api/songs/channel/1")) {
            List<?> rows = http.get().uri(URI.create("http://localhost:" + port + path)).retrieve().body(List.class);
            assertThat(rows).hasSize(1);
            Map<?, ?> row = (Map<?, ?>) rows.getFirst();
            assertThat(row.get("firstPlayed")).isEqualTo("2026-11-01T09:00:00Z");
            assertThat(row.get("lastPlayed")).isEqualTo("2026-11-01T09:00:00Z");
        }
    }

    @Test
    void invalidQueriesReturn400RatherThanSilentlyIgnoringThem() {
        for (String query : List.of("page=-1", "size=0", "size=50000", "sort=notAColumn",
                "direction=sideways", "channel=9", "page=abc", "timeZone=Not/AZone", "q=" + "x".repeat(201))) {
            assertBadRequest("/api/records/tracks?" + query);
        }
        assertBadRequest("/api/records/plays?from=2026-11-02&to=2026-11-01");
        assertBadRequest("/api/records/plays?from=2026-02-30");
    }

    @Test
    void emptyHistoryIsAnHonestEmptyPage() {
        Map<?, ?> page = get("/api/records/plays");
        assertThat(items(page)).isEmpty();
        assertThat(((Number) page.get("totalElements")).longValue()).isZero();
        assertThat(page.get("historyStartedAt")).isNull();
    }

    @Test
    void recordingIsDurableAcrossContextsAndIndependentForEachChannel() {
        RadioParadiseResponse a = response("A");
        Song first = service.saveOrUpdateSong(a, "1");
        // No shared entity-manager or scheduler memory between these transactions.
        service.saveOrUpdateSong(a, "1");
        assertThat(plays.count()).isEqualTo(1);
        assertThat(songs.findById(first.getId()).orElseThrow().getPlayCount()).isEqualTo(1);
        service.saveOrUpdateSong(a, "2");
        service.saveOrUpdateSong(response("B"), "1");
        service.saveOrUpdateSong(a, "1");
        assertThat(plays.count()).isEqualTo(4);
        assertThat(songs.findById(first.getId()).orElseThrow().getPlayCount()).isEqualTo(2);
    }

    @Test
    void songSummaryAndPlaybackEventRollbackTogether() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.saveOrUpdateSong(response("Rolled back"), "0");
            throw new IllegalStateException("abort transaction");
        })).hasMessage("abort transaction");
        assertThat(songs.count()).isZero();
        assertThat(plays.count()).isZero();
    }

    private Song song(String title, String channel) {
        return Song.builder().title(title).artist("Artist").album("Album").year("2026")
                .channel(channel).firstPlayed(LocalDateTime.of(2026, 11, 1, 12, 0))
                .lastPlayed(LocalDateTime.of(2026, 11, 1, 12, 0)).playCount(1).build();
    }

    private RadioParadiseResponse response(String title) {
        RadioParadiseResponse response = new RadioParadiseResponse();
        response.setTitle(title);
        response.setArtist("Artist");
        response.setAlbum("Album");
        return response;
    }

    private Map<?, ?> get(String path) {
        return http.get().uri(URI.create("http://localhost:" + port + path)).retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<?, ?> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private void assertBadRequest(String path) {
        assertThatThrownBy(() -> get(path)).isInstanceOfSatisfying(RestClientResponseException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(400));
    }
}
