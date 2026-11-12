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
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.ArrayList;
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
    void playbackHistoryDistinguishesBroadcastTimesFromTrackerObservations() {
        Song track = songs.save(song("Archived song", "0"));
        Instant broadcastAt = Instant.parse("2024-01-02T10:20:30Z");
        plays.save(PlaybackEvent.builder().song(track).channel("0")
                .observedAt(broadcastAt).rpSourceKey("a".repeat(64)).build());
        plays.save(PlaybackEvent.builder().song(track).channel("0")
                .observedAt(broadcastAt.plusSeconds(300)).build());

        Map<?, ?> page = get("/api/records/plays?direction=asc");

        assertThat(items(page).get(0).get("timeSource")).isEqualTo("broadcast");
        assertThat(items(page).get(0).get("playedAt")).isEqualTo(broadcastAt.toString());
        assertThat(items(page).get(1).get("timeSource")).isEqualTo("observed");
    }

    @Test
    void libraryCombinesChannelStatisticsWithoutChangingTracksOrPlays() {
        Song main = song("Shared", "0");
        main.setPlayCount(Integer.MAX_VALUE);
        main.setFirstPlayed(Instant.parse("2026-10-01T09:00:00Z"));
        Song rock = song("Shared", "2");
        rock.setPlayCount(3);
        rock.setLastPlayed(Instant.parse("2026-11-02T09:00:00Z"));
        songs.saveAll(List.of(main, rock));
        plays.save(PlaybackEvent.builder().song(rock).channel("2")
                .observedAt(rock.getLastPlayed()).build());
        Song otherAlbum = song("Shared", "1");
        otherAlbum.setAlbum("Live album");
        Song otherArtist = song("Shared", "1");
        otherArtist.setArtist("Other artist");
        songs.saveAll(List.of(otherAlbum, otherArtist));

        Map<?, ?> page = get("/api/songs/library?sort=playCount");

        assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(3);
        Map<String, Object> shared = items(page).getFirst();
        assertThat(((Number) shared.get("id")).longValue()).isEqualTo(main.getId());
        assertThat(((Number) shared.get("playCount")).longValue())
                .isEqualTo((long) Integer.MAX_VALUE + 3);
        assertThat(Instant.parse((String) shared.get("firstPlayed"))).isEqualTo(main.getFirstPlayed());
        assertThat(Instant.parse((String) shared.get("lastPlayed"))).isEqualTo(rock.getLastPlayed());
        assertThat(shared).doesNotContainKey("channel");
        assertThat(items(get("/api/records/tracks"))).hasSize(4);
        assertThat(items(get("/api/records/tracks?channel=2"))).hasSize(1);
        assertThat(items(get("/api/records/plays"))).hasSize(1);
        assertThat(songs.findById(main.getId()).orElseThrow().getPlayCount()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void libraryGroupsBeforePagingAndUsesStableAggregateSorting() {
        List<Song> tracks = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            Song main = song("Song " + i, "0");
            Song mellow = song("Song " + i, "1");
            mellow.setPlayCount(i + 1);
            tracks.add(main);
            tracks.add(mellow);
        }
        songs.saveAll(tracks);

        Map<?, ?> first = get("/api/songs/library?sort=playCount&size=25");
        Map<?, ?> second = get("/api/songs/library?sort=playCount&size=25&page=1");

        assertThat(((Number) first.get("totalElements")).longValue()).isEqualTo(31);
        assertThat(((Number) first.get("totalPages")).intValue()).isEqualTo(2);
        assertThat(items(first)).hasSize(25);
        assertThat(items(second)).hasSize(6);
        assertThat(items(first).getFirst().get("title")).isEqualTo("Song 30");
        assertThat(items(second).getLast().get("title")).isEqualTo("Song 0");
        var recent = items(get("/api/songs/library?sort=lastPlayed"));
        assertThat(recent).extracting(row -> ((Number) row.get("id")).longValue())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(items(get("/api/songs/library?page=8"))).isEmpty();
        for (String sort : List.of("id", "title", "artist", "album", "year", "firstPlayed", "lastPlayed", "playCount")) {
            assertThat(items(get("/api/songs/library?sort=" + sort + "&direction=asc"))).hasSize(25);
        }
    }

    @Test
    void librarySearchRetainsLiteralFuzzyAccentAndWildcardSemantics() {
        Song main = song("Echoes", "0");
        main.setArtist("Pink Floyd");
        Song rock = song("Echoes", "2");
        rock.setArtist("Pink Floyd");
        Song accented = song("Café 100%", "1");
        songs.saveAll(List.of(main, rock, accented));

        assertThat(items(get("/api/songs/library?q=Pink"))).hasSize(1);
        assertThat(items(get("/api/songs/library?q=Pinnk"))).hasSize(1);
        assertThat(items(get("/api/songs/library?q=cafe"))).hasSize(1);
        assertThat(items(get("/api/songs/library?q=100%25"))).hasSize(1);
        assertThat(items(get("/api/songs/library?q=zzzzzzzzzz"))).isEmpty();
        assertThat(((Number) items(get("/api/songs/library?q=Pink")).getFirst().get("playCount"))
                .longValue()).isEqualTo(2);
    }

    @Test
    void libraryUsesCachedSnapshotsNoStoreAndValidatesQueries() {
        Song song = songs.save(song("Cached library", "1"));
        var response = http.get().uri("http://localhost:" + port + "/api/songs/library")
                .retrieve().toEntity(Map.class);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        song.setPlayCount(10);
        songs.save(song);
        assertThat(((Number) items(get("/api/songs/library")).getFirst().get("playCount"))
                .intValue()).isEqualTo(1);
        songController.clearCache();
        assertThat(((Number) items(get("/api/songs/library")).getFirst().get("playCount"))
                .intValue()).isEqualTo(10);
        for (String query : List.of("channel=1", "sort=channel", "sort=invalid", "page=-1", "size=500", "direction=sideways")) {
            assertBadRequest("/api/songs/library?" + query);
        }
    }

    @Test
    void schemaSetupCreatesValidIndexesForGlobalAndChannelRecency() {
        var definitions = jdbc.queryForList(
                "SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i "
                        + "JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE i.indrelid = 'song'::regclass AND i.indisvalid AND i.indisready "
                        + "AND c.relname IN ('song_last_played_id_idx', 'song_channel_last_played_id_idx') "
                        + "ORDER BY c.relname",
                String.class);

        assertThat(definitions).containsExactly(
                "CREATE INDEX song_channel_last_played_id_idx ON public.song USING btree (channel, last_played DESC, id DESC)",
                "CREATE INDEX song_last_played_id_idx ON public.song USING btree (last_played DESC, id DESC)");

        var searchIndexes = jdbc.queryForList(
                "SELECT c.relname FROM pg_index i "
                        + "JOIN pg_class c ON c.oid = i.indexrelid "
                        + "WHERE i.indrelid = 'song'::regclass AND i.indisvalid AND i.indisready "
                        + "AND c.relname IN ('song_search_trgm_idx', 'song_search_phonetic_idx') "
                        + "ORDER BY c.relname",
                String.class);
        assertThat(searchIndexes).containsExactly("song_search_phonetic_idx", "song_search_trgm_idx");

        String plan = transactions.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            jdbc.execute("SET LOCAL pg_trgm.strict_word_similarity_threshold = 0.4");
            return String.join("\n", jdbc.queryForList(
                    "EXPLAIN SELECT s.id FROM song s WHERE ("
                            + "public.f_song_search_text(s.title, s.artist, s.album) LIKE public.f_unaccent('%pinnk%') ESCAPE '\\' "
                            + "OR public.f_song_search_text(s.title, s.artist, s.album) operator(public.%>>) public.f_unaccent('Pinnk') "
                            + "OR public.f_phonetic_codes(s.title, s.artist, s.album) && public.f_query_phonetic_codes('Pinnk'))",
                    String.class));
        });
        assertThat(plan).contains("BitmapOr", "song_search_trgm_idx", "song_search_phonetic_idx");
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
        Song sheep = song("Sheep", "1");
        sheep.setArtist("Pink Floyd");
        sheep.setAlbum("Animals");
        Song dogs = song("Dogs", "1");
        dogs.setArtist("Pink Floyd");
        dogs.setAlbum("Animals");
        Song sigur = song("Hoppípolla", "1");
        sigur.setArtist("Sigur Rós");
        sigur.setAlbum("Takk...");
        Song philip = song("Opening", "1");
        philip.setArtist("Philip Glass");
        philip.setAlbum("Glassworks");
        songs.saveAll(List.of(song("100% Pure", "1"), song("100 Percent", "1"), song("100% Pure", "2"),
                song("under_score", "1"), song("underXscore", "1"), sheep, dogs, sigur, philip));
        plays.save(PlaybackEvent.builder().song(sheep).channel("1")
                .observedAt(Instant.parse("2026-11-01T09:00:00Z")).build());
        List<Map<String, Object>> rows = items(get("/api/records/tracks?q=100%25&channel=1"));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("title")).isEqualTo("100% Pure");
        assertThat(rows.getFirst().get("channel")).isEqualTo("1");
        assertThat(items(get("/api/records/tracks?q=under_score"))).hasSize(1);
        assertThat(items(get("/api/records/tracks?q=ALBUM&channel=2"))).hasSize(1);
        assertThat(items(get("/api/records/tracks?q=Pink+Floyd")))
                .extracting(row -> row.get("title"))
                .containsExactlyInAnyOrder("Sheep", "Dogs");
        assertThat(items(get("/api/records/tracks?q=Pin+Floyd")))
                .extracting(row -> row.get("title"))
                .containsExactlyInAnyOrder("Sheep", "Dogs");
        assertThat(items(get("/api/records/tracks?q=Pinnk+Flyd")))
                .extracting(row -> row.get("title"))
                .containsExactlyInAnyOrder("Sheep", "Dogs");
        List<Map<String, Object>> typoMatches = items(get("/api/records/tracks?q=Oink+Floyd"));
        assertThat(typoMatches)
                .extracting(row -> row.get("title"))
                .containsExactlyInAnyOrder("Sheep", "Dogs");
        assertThat(typoMatches).extracting(row -> row.get("artist")).containsOnly("Pink Floyd");
        assertThat(items(get("/api/records/tracks?q=Pink+Floyd+Sheep")))
                .extracting(row -> row.get("title"))
                .containsExactly("Sheep");
        assertThat(items(get("/api/records/tracks?q=Pin+Floyd+Shep")))
                .extracting(row -> row.get("title"))
                .containsExactly("Sheep");
        assertThat(items(get("/api/records/tracks?q=Pnk+Flod+Shep")))
                .extracting(row -> row.get("title"))
                .containsExactly("Sheep");
        assertThat(items(get("/api/records/tracks?q=Sheep+Animals+Floyd")))
                .extracting(row -> row.get("title"))
                .containsExactly("Sheep");
        assertThat(items(get("/api/records/tracks?q=Sigur+Ros+Hoppipolla")))
                .extracting(row -> row.get("artist"))
                .containsExactly("Sigur Rós");
        assertThat(items(get("/api/records/tracks?q=Filip+Glas")))
                .extracting(row -> row.get("artist"))
                .containsExactly("Philip Glass");
        assertThat(items(get("/api/records/tracks?q=CompletelyUnrelatedQuery"))).isEmpty();
        assertThat(items(get("/api/records/tracks?q=Pink+Piano"))).isEmpty();
        assertThat(items(get("/api/records/plays?q=Pin+Floyd+Shep")))
                .extracting(row -> row.get("title"))
                .containsExactly("Sheep");
        assertThat(items(get("/api/records/plays?q=Oink+Floyd")))
                .extracting(row -> row.get("artist"))
                .containsExactly("Pink Floyd");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tracks", "plays"})
    void literalArtistAndTitleMatchesExcludeFuzzyRecordsAndKeepPaginationTotals(String endpoint) {
        Song target = song("There There", "2");
        target.setArtist("Radiohead");
        target.setAlbum("Hail to the Thief");
        Song otherChannel = song("There There", "0");
        otherChannel.setArtist("Radiohead");
        otherChannel.setAlbum("Hail to the Thief");
        Song clocks = song("Clocks", "1");
        clocks.setArtist("Coldplay");
        clocks.setAlbum("A Rush of Blood to the Head");
        Song radio = song("Radio Nowhere", "2");
        radio.setArtist("Bruce Springsteen");
        radio.setAlbum("Magic");
        List<Song> records = new ArrayList<>(List.of(target, otherChannel, clocks, radio));
        for (int i = 0; i < 30; i++) {
            Song unrelated = song("High and Dry " + i, "2");
            unrelated.setArtist("Radiohead");
            unrelated.setAlbum("The Bends");
            records.add(unrelated);
        }
        for (Song saved : songs.saveAll(records)) {
            plays.save(PlaybackEvent.builder().song(saved).channel(saved.getChannel())
                    .observedAt(Instant.parse("2026-11-01T09:00:00Z")).build());
        }

        for (String search : List.of("Radiohead+There+There", "THERE+radiohead", "Radiohed+There+There")) {
            Map<?, ?> page = get("/api/records/" + endpoint + "?q=" + search);
            assertThat(items(page)).extracting(row -> row.get("title"))
                    .containsExactly("There There", "There There");
            assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(2);
            Map<?, ?> beyondLastPage = get("/api/records/" + endpoint + "?q=" + search + "&page=1");
            assertThat(items(beyondLastPage)).isEmpty();
            assertThat(((Number) beyondLastPage.get("totalElements")).longValue()).isEqualTo(2);
        }
        assertThat(items(get("/api/records/" + endpoint + "?q=Radiohead+There+There&channel=2")))
                .extracting(row -> row.get("channel")).containsExactly("2");
    }

    @Test
    void literalMatchesInOtherChannelsDoNotSuppressTypoFallback() {
        Song literal = song("Pinnk", "0");
        Song typo = song("Sheep", "1");
        typo.setArtist("Pink Floyd");
        songs.saveAll(List.of(literal, typo));

        assertThat(items(get("/api/records/tracks?q=Pinnk")))
                .extracting(row -> row.get("title")).containsExactly("Pinnk");
        assertThat(items(get("/api/records/tracks?q=Pinnk&channel=1")))
                .extracting(row -> row.get("title")).containsExactly("Sheep");
    }

    @Test
    void literalMatchesOutsideHistoryDatesDoNotSuppressTypoFallback() {
        Song literal = songs.save(song("Pinnk", "1"));
        Song typo = song("Sheep", "1");
        typo.setArtist("Pink Floyd");
        typo = songs.save(typo);
        plays.save(PlaybackEvent.builder().song(literal).channel("1")
                .observedAt(Instant.parse("2026-11-01T09:00:00Z")).build());
        plays.save(PlaybackEvent.builder().song(typo).channel("1")
                .observedAt(Instant.parse("2026-11-02T09:00:00Z")).build());

        assertThat(items(get("/api/records/plays?q=Pinnk&channel=1")))
                .extracting(row -> row.get("title")).containsExactly("Pinnk");
        Map<?, ?> page = get("/api/records/plays?q=Pinnk&channel=1&from=2026-11-02&to=2026-11-02");
        assertThat(items(page)).extracting(row -> row.get("title")).containsExactly("Sheep");
        assertThat(page.get("historyStartedAt")).isEqualTo("2026-11-01T09:00:00Z");
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
