package com.radioparadise.tracker.records;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.dto.Dashboard;
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
    @Autowired com.radioparadise.tracker.service.RecordingBackfillService backfill;
    @Autowired TransactionTemplate transactions;
    @Autowired Environment environment;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.radioparadise.tracker.controller.RecordsController recordsController;
    @Autowired com.radioparadise.tracker.controller.SongController songController;
    @MockitoBean SongTrackerScheduler scheduler;
    @MockitoBean com.radioparadise.tracker.client.LyricsClient lyricsClient;
    @MockitoBean com.radioparadise.tracker.client.LrclibClient lrclibClient;
    @MockitoBean com.radioparadise.tracker.client.MusicBrainzClient musicBrainzClient;
    RestClient http = RestClient.create();

    @BeforeEach
    void clearTestDatabase() {
        org.mockito.Mockito.when(musicBrainzClient.find(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.radioparadise.tracker.client.MusicBrainzClient.Result.unresolved("not_found"));
        recordsController.clearCaches();
        songController.clearCache();
        assertThat(environment.getRequiredProperty("spring.datasource.url"))
                .as("Integration tests must only use the disposable records_test database")
                .endsWith("/records_test");
        plays.deleteAll();
        songs.deleteAll();
    }

    @Test
    void detailsArePersistedAndApiCachesUseSnapshots() {
        Song song = songs.save(song("Details", "1"));
        org.mockito.Mockito.when(lyricsClient.find(null, "Artist", "Details", "Album", "1"))
                .thenReturn(new com.radioparadise.tracker.client.LyricsClient.Result("found", "Sample lyrics", "https://radioparadise.com/music/song/12", 120.0, 12L));
        String source = "https://musicbrainz.org/recording/5a6cd4c6-3d1f-4d71-8803-1b327674fb86";
        String spotify = "https://open.spotify.com/track/0123456789ABCDEFGHIJKL";
        org.mockito.Mockito.when(musicBrainzClient.find("Artist", "Details", "Album", 120.0))
                .thenReturn(new com.radioparadise.tracker.client.MusicBrainzClient.Result("matched", "GBABC1200001", source, spotify, null));
        Map<?, ?> details = get("/api/songs/" + song.getId());
        assertThat(details.get("lyrics")).isEqualTo("Sample lyrics");
        Song enriched = songs.findById(song.getId()).orElseThrow();
        assertThat(enriched.getLyrics()).isEqualTo("Sample lyrics");
        assertThat(enriched.getRpSongId()).isEqualTo(12);
        assertThat(enriched.getLyricsSource()).isEqualTo("https://radioparadise.com/music/song/12");
        assertThat(enriched.getIsrc()).isEqualTo("GBABC1200001");
        assertThat(enriched.getRecordingMatchSource()).isEqualTo(source);
        assertThat(enriched.getSpotifyUrl()).isEqualTo(spotify);
        assertThat(enriched.getRecordingCheckedAt()).isNotNull();
        assertThat(((Map<?, ?>) details.get("recordingLinks")).get("isrc")).isEqualTo("GBABC1200001");
        get("/api/songs/" + song.getId());
        org.mockito.Mockito.verify(lyricsClient, org.mockito.Mockito.times(1)).find(null, "Artist", "Details", "Album", "1");
        org.mockito.Mockito.verify(musicBrainzClient, org.mockito.Mockito.times(1)).find("Artist", "Details", "Album", 120.0);
        assertThat(items(get("/api/records/tracks"))).hasSize(1);
        song.setTitle("Changed later");
        songs.save(song);
        assertThat(items(get("/api/records/tracks")).getFirst().get("title")).isEqualTo("Details");
        recordsController.clearCaches();
        assertThat(items(get("/api/records/tracks")).getFirst().get("title")).isEqualTo("Changed later");
    }

    @Test
    void fallbackLyricsPersistWithoutChangingStatisticsAndRpCanReplaceThem() {
        Song song = songs.save(song("Fallback details", "1"));
        String source = "https://lrclib.net/api/get/123";
        org.mockito.Mockito.when(lyricsClient.find(null, "Artist", "Fallback details", "Album", "1"))
                .thenReturn(new com.radioparadise.tracker.client.LyricsClient.Result(
                        "not_found", null, "https://radioparadise.com/music/song/12", 120.0, 12L));
        org.mockito.Mockito.when(lrclibClient.find("Artist", "Fallback details", "Album", 120.0))
                .thenReturn(new com.radioparadise.tracker.client.LrclibClient.Result("found", "Fallback text", source));

        Map<?, ?> details = get("/api/songs/" + song.getId());

        assertThat(details.get("lyrics")).isEqualTo("Fallback text");
        assertThat(details.get("lyricsSource")).isEqualTo(source);
        Song enriched = songs.findById(song.getId()).orElseThrow();
        assertThat(enriched.getLyrics()).isEqualTo("Fallback text");
        assertThat(enriched.getLyricsSource()).isEqualTo(source);
        assertThat(enriched.getRpSongId()).isEqualTo(12L);
        assertThat(enriched.getDurationSeconds()).isEqualTo(120.0);
        assertThat(enriched.getPlayCount()).isEqualTo(song.getPlayCount());
        assertThat(enriched.getFirstPlayed()).isEqualTo(song.getFirstPlayed());
        assertThat(enriched.getLastPlayed()).isEqualTo(song.getLastPlayed());
        assertThat(plays.count()).isZero();

        // Expire the persisted fallback and the independent HTTP response cache.
        jdbc.update("update song set metadata_checked_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(86401)), song.getId());
        songController.clearCache();
        org.mockito.Mockito.when(lyricsClient.find(12L, "Artist", "Fallback details", "Album", "1"))
                .thenReturn(new com.radioparadise.tracker.client.LyricsClient.Result(
                        "found", "RP text", "https://radioparadise.com/music/song/12", 120.0, 12L));

        assertThat(get("/api/songs/" + song.getId()).get("lyrics")).isEqualTo("RP text");
        assertThat(songs.findById(song.getId()).orElseThrow().getLyricsSource())
                .isEqualTo("https://radioparadise.com/music/song/12");
        org.mockito.Mockito.verify(lrclibClient, org.mockito.Mockito.times(1))
                .find("Artist", "Fallback details", "Album", 120.0);
    }

    @Test
    void collectingAPlayDoesNotOverwriteConcurrentLyricsEnrichment() {
        Song song = songs.save(song("Concurrent enrichment", "1"));
        transactions.executeWithoutResult(tx -> {
            Song managed = songs.findById(song.getId()).orElseThrow();
            var independent = new TransactionTemplate(transactions.getTransactionManager());
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(other -> songs.updateDetails(song.getId(), "Saved lyrics", "found", "source", 100.0, Instant.now(), 12L));
            managed.setPlayCount(2);
            managed.setLastPlayed(Instant.now());
        });
        Song result = songs.findById(song.getId()).orElseThrow();
        assertThat(result.getLyrics()).isEqualTo("Saved lyrics");
        assertThat(result.getPlayCount()).isEqualTo(2);
    }

    @Test
    void recordingEnrichmentAndPlaybackStatisticsDoNotOverwriteEachOther() {
        Song song = songs.save(song("Concurrent recording links", "1"));
        Instant firstPlayed = song.getFirstPlayed();
        transactions.executeWithoutResult(tx -> {
            Song managed = songs.findById(song.getId()).orElseThrow();
            var independent = new TransactionTemplate(transactions.getTransactionManager());
            independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            independent.executeWithoutResult(other -> songs.updateRecordingLinks(song.getId(), "GBABC1200001",
                    "matched", "source", "spotify", null, Instant.now()));
            managed.setPlayCount(2);
            managed.setLastPlayed(Instant.now());
        });
        Song result = songs.findById(song.getId()).orElseThrow();
        assertThat(result.getIsrc()).isEqualTo("GBABC1200001");
        assertThat(result.getSpotifyUrl()).isEqualTo("spotify");
        assertThat(result.getPlayCount()).isEqualTo(2);
        assertThat(result.getFirstPlayed()).isEqualTo(firstPlayed);
        songs.updateRecordingLinks(song.getId(), "GBABC1200001", "unavailable", "source", "spotify", null, Instant.now());
        Song refreshed = songs.findById(song.getId()).orElseThrow();
        assertThat(refreshed.getLastPlayed()).isEqualTo(result.getLastPlayed());
        assertThat(refreshed.getPlayCount()).isEqualTo(2);
    }

    @Test
    void backfillContinuesAfterCatalogErrorsAndRetriesOnlyFailures() {
        Song failed = song("Catalog error", "1");
        Song matched = song("Catalog match", "1");
        for (Song song : List.of(failed, matched)) {
            song.setDurationSeconds(120.0);
            song.setLyricsStatus("found");
            song.setLyricsSource("https://radioparadise.com/music/song/12");
            song.setMetadataCheckedAt(Instant.now());
            songs.save(song);
        }
        org.mockito.Mockito.when(musicBrainzClient.find("Artist", "Catalog error", "Album", 120.0))
                .thenReturn(com.radioparadise.tracker.client.MusicBrainzClient.Result.unresolved("unavailable"));
        org.mockito.Mockito.when(musicBrainzClient.find("Artist", "Catalog match", "Album", 120.0))
                .thenReturn(new com.radioparadise.tracker.client.MusicBrainzClient.Result("matched", "GBABC1200001", "source", null, null));

        var initial = backfill.run(0, 100);

        assertThat(initial.processed()).isEqualTo(2);
        assertThat(initial.withIsrc()).isEqualTo(1);
        assertThat(initial.unavailable()).isEqualTo(1);
        assertThat(songs.findIdsWithoutIsrc(0, songs.findHighestId(), org.springframework.data.domain.PageRequest.of(0, 50)))
                .containsExactly(failed.getId());
        jdbc.update("update song set recording_checked_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(301)), failed.getId());
        org.mockito.Mockito.when(musicBrainzClient.find("Artist", "Catalog error", "Album", 120.0))
                .thenReturn(new com.radioparadise.tracker.client.MusicBrainzClient.Result("matched", "GBABC1200002", "source", null, null));

        var retry = backfill.retryFailures(100);

        assertThat(retry.processed()).isEqualTo(1);
        assertThat(retry.withIsrc()).isEqualTo(1);
        assertThat(songs.findIdsWithRecordingErrors(0, songs.findHighestId(), org.springframework.data.domain.PageRequest.of(0, 50))).isEmpty();
        assertThat(songs.findIdsWithoutIsrc(0, failed.getId(), org.springframework.data.domain.PageRequest.of(0, 50))).isEmpty();
        assertThat(songs.findById(failed.getId()).orElseThrow().getPlayCount()).isEqualTo(1);
        assertThat(plays.count()).isZero();
        org.mockito.Mockito.verifyNoInteractions(lyricsClient);
    }

    @Test
    void dashboardLimitsLatestTracksButRetainsArchiveSummariesAndBrowsing() {
        List<Song> recent = songs.saveAll(IntStream.range(0, 501)
                .mapToObj(i -> song("Recent " + i, "1")).toList());
        Song older = song("Older favorite", "0");
        older.setYear("1975");
        older.setLastPlayed(older.getLastPlayed().minusSeconds(3600));
        older.setFirstPlayed(older.getLastPlayed());
        older.setPlayCount(100);
        Song unknownYear = song("Unknown year", "2");
        unknownYear.setYear(null);
        unknownYear.setLastPlayed(older.getLastPlayed());
        unknownYear.setFirstPlayed(older.getLastPlayed());
        songs.saveAll(List.of(older, unknownYear));

        var response = http.get().uri("http://localhost:" + port + "/api/songs/dashboard")
                .retrieve().toEntity(Dashboard.class);
        var dashboard = response.getBody();

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(dashboard.latestTracks()).hasSize(500);
        assertThat(dashboard.latestTracks().getFirst().id()).isEqualTo(recent.getLast().getId());
        assertThat(dashboard.latestTracks().getLast().id()).isEqualTo(recent.get(1).getId());
        assertThat(dashboard.totalTracks()).isEqualTo(503);
        assertThat(dashboard.tracksByChannel()).isEqualTo(Map.of("0", 1L, "1", 501L, "2", 1L));
        assertThat(dashboard.tracksByDecade()).isEqualTo(Map.of("70s & Earlier", 1L, "2020s", 501L));
        assertThat(get("/api/records/tracks").get("totalElements")).isEqualTo(503);
        List<?> all = http.get().uri("http://localhost:" + port + "/api/songs")
                .retrieve().body(List.class);
        assertThat(all).hasSize(503);
        List<?> mostPlayed = http.get().uri("http://localhost:" + port + "/api/songs/most-played")
                .retrieve().body(List.class);
        assertThat(((Map<?, ?>) mostPlayed.getFirst()).get("title")).isEqualTo("Older favorite");

        Song changed = recent.getLast();
        changed.setTitle("Changed later");
        changed.setYear("1985");
        songs.save(changed);
        assertThat(http.get().uri("http://localhost:" + port + "/api/songs/dashboard")
                .retrieve().body(Dashboard.class)).isEqualTo(dashboard);
        songController.clearCache();
        var refreshed = http.get().uri("http://localhost:" + port + "/api/songs/dashboard")
                .retrieve().body(Dashboard.class);
        assertThat(refreshed.latestTracks().getFirst().title()).isEqualTo("Changed later");
        assertThat(refreshed.tracksByDecade().get("80s")).isEqualTo(1L);
    }

    @Test
    void emptyDashboardReturnsZeroTotals() {
        var dashboard = http.get().uri("http://localhost:" + port + "/api/songs/dashboard")
                .retrieve().body(Dashboard.class);

        assertThat(dashboard.latestTracks()).isEmpty();
        assertThat(dashboard.totalTracks()).isZero();
        assertThat(dashboard.tracksByChannel()).isEmpty();
        assertThat(dashboard.tracksByDecade()).isEmpty();
    }

    @Test
    void currentEndpointReturnsLatestPerChannelAndSongDetailsUse404ForUnknownIds() {
        songs.saveAll(List.of(song("Main", "0"), song("Mellow", "1"), song("Rock", "2")));
        List<?> rows = http.get().uri("http://localhost:" + port + "/api/songs/current").retrieve().body(List.class);
        assertThat(rows).hasSize(3);
        assertThatThrownBy(() -> get("/api/songs/9223372036854775807"))
                .isInstanceOfSatisfying(RestClientResponseException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT s.id FROM song s JOIN playback_event p ON p.song_id=s.id GROUP BY s.id HAVING s.first_played <> min(p.observed_at) OR s.last_played <> max(p.observed_at) OR s.play_count <> count(*)) mismatches", Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND ((table_name='song' AND column_name IN ('first_played','last_played')) OR (table_name='playback_event' AND column_name='observed_at'))", String.class))
                .containsExactlyInAnyOrder("timestamp with time zone", "timestamp with time zone", "timestamp with time zone");
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
                .channel(channel).firstPlayed(Instant.parse("2026-11-01T09:00:00Z"))
                .lastPlayed(Instant.parse("2026-11-01T09:00:00Z")).playCount(1).build();
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
