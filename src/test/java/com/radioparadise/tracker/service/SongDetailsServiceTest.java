package com.radioparadise.tracker.service;

import com.radioparadise.tracker.client.LrclibClient;
import com.radioparadise.tracker.client.LyricsClient;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SongDetailsServiceTest {
    private static final String RP_SOURCE = LyricsClient.SOURCE + "/song/42";
    private static final String LRCLIB_SOURCE = "https://lrclib.net/api/get/123";
    private final SongRepository songs = mock(SongRepository.class);
    private final LyricsClient lyrics = mock(LyricsClient.class);
    private final LrclibClient fallback = mock(LrclibClient.class);
    private final RecordingLinksService recordingLinks = mock(RecordingLinksService.class);
    private final SongDetailsService service = new SongDetailsService(songs, lyrics, fallback, recordingLinks);
    private Song song;

    @BeforeEach
    void setup() {
        song = Song.builder().id(1L).rpSongId(42L).channel("1").artist("Artist").title("Title")
                .album("Album").playCount(7).build();
        lenient().when(songs.findById(1L)).thenAnswer(call -> Optional.of(song));
        lenient().when(fallback.find(anyString(), anyString(), anyString(), any()))
                .thenReturn(LrclibClient.Result.unresolved("not_found"));
        lenient().doAnswer(call -> {
            song.setLyrics(call.getArgument(1));
            song.setLyricsStatus(call.getArgument(2));
            song.setLyricsSource(call.getArgument(3));
            song.setDurationSeconds(call.getArgument(4));
            song.setMetadataCheckedAt(call.getArgument(5));
            if (call.getArgument(6) != null) {
                song.setRpSongId(call.getArgument(6));
            }
            return null;
        }).when(songs).updateDetails(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void persistsLyricsOnceWithoutSavingOrChangingListeningStats() {
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("found"));

        assertThat(service.get(1).lyrics()).isEqualTo("RP text");
        assertThat(service.get(1).song().playCount()).isEqualTo(7);

        verify(lyrics, times(1)).find(42L, "Artist", "Title", "Album", "1");
        verify(songs, never()).save(any());
        verify(songs).updateDetails(eq(1L), eq("RP text"), eq("found"), eq(RP_SOURCE), eq(123.0), any(), eq(42L));
        verifyNoInteractions(fallback);
    }

    @Test
    void rpInstrumentalIsAuthoritativeAndDoesNotCallFallback() {
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("instrumental"));

        assertThat(service.get(1).lyricsStatus()).isEqualTo("instrumental");

        verifyNoInteractions(fallback);
    }

    @Test
    void cachesMissingResultsAcrossRequestsAndRetriesAfterOneDay() {
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("not_found"));
        service.get(1);
        service.get(1);
        verify(lyrics, times(1)).find(42L, "Artist", "Title", "Album", "1");
        verify(fallback, times(1)).find("Artist", "Title", "Album", 123.0);

        song.setMetadataCheckedAt(Instant.now().minus(Duration.ofDays(2)));
        service.get(1);

        verify(lyrics, times(2)).find(42L, "Artist", "Title", "Album", "1");
    }

    @Test
    void providerFailureIsRetriedAfterFiveMinutes() {
        song.setLyricsStatus("unavailable");
        song.setLyricsSource(RP_SOURCE);
        song.setMetadataCheckedAt(Instant.now().minusSeconds(60));
        service.get(1);
        verifyNoInteractions(lyrics, fallback);

        song.setMetadataCheckedAt(Instant.now().minusSeconds(301));
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("unavailable"));

        assertThat(service.get(1).lyricsStatus()).isEqualTo("unavailable");
        verify(lyrics).find(42L, "Artist", "Title", "Album", "1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not_found", "unavailable"})
    void usesFallbackWhenRpCannotSupplyLyrics(String rpStatus) {
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp(rpStatus));
        when(fallback.find("Artist", "Title", "Album", 123.0))
                .thenReturn(new LrclibClient.Result("found", "Fallback text", LRCLIB_SOURCE));

        var result = service.get(1);

        assertThat(result.lyrics()).isEqualTo("Fallback text");
        assertThat(result.lyricsSource()).isEqualTo(LRCLIB_SOURCE);
        assertThat(result.durationSeconds()).isEqualTo(123.0);
        assertThat(song.getRpSongId()).isEqualTo(42L);
        assertThat(song.getPlayCount()).isEqualTo(7);
        service.get(1);
        verify(fallback, times(1)).find("Artist", "Title", "Album", 123.0);
    }

    @Test
    void fallbackInstrumentalIsPersistedWithItsSource() {
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("not_found"));
        when(fallback.find("Artist", "Title", "Album", 123.0))
                .thenReturn(new LrclibClient.Result("instrumental", null, LRCLIB_SOURCE));

        var result = service.get(1);

        assertThat(result.lyricsStatus()).isEqualTo("instrumental");
        assertThat(result.lyrics()).isNull();
        assertThat(result.lyricsSource()).isEqualTo(LRCLIB_SOURCE);
    }

    @Test
    void usesSavedDurationForLegacySongWithoutAnRpId() {
        song.setRpSongId(null);
        song.setDurationSeconds(120.0);
        when(lyrics.find(null, "Artist", "Title", "Album", "1"))
                .thenReturn(new LyricsClient.Result("not_found", null, LyricsClient.SOURCE, null, null));
        when(fallback.find("Artist", "Title", "Album", 120.0))
                .thenReturn(new LrclibClient.Result("found", "Fallback text", LRCLIB_SOURCE));

        assertThat(service.get(1).lyrics()).isEqualTo("Fallback text");
        assertThat(song.getRpSongId()).isNull();
        assertThat(song.getDurationSeconds()).isEqualTo(120.0);
    }

    @ParameterizedTest
    @CsvSource({"not_found,not_found,not_found", "not_found,unavailable,unavailable",
            "unavailable,not_found,unavailable", "unavailable,unavailable,unavailable"})
    void distinguishesMissingLyricsFromProviderFailures(String rpStatus, String fallbackStatus, String expected) {
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp(rpStatus));
        when(fallback.find("Artist", "Title", "Album", 123.0))
                .thenReturn(LrclibClient.Result.unresolved(fallbackStatus));

        var result = service.get(1);

        assertThat(result.lyricsStatus()).isEqualTo(expected);
        assertThat(result.lyricsSource()).isEqualTo(RP_SOURCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {RP_SOURCE, LRCLIB_SOURCE})
    void temporaryRpFailurePreservesSavedLyricsWithoutCallingFallback(String source) {
        saveLyrics(source, "found");
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("unavailable"));

        assertThat(service.get(1).lyrics()).isEqualTo("Saved text");
        assertThat(song.getLyricsSource()).isEqualTo(source);
        verifyNoInteractions(fallback);
    }

    @ParameterizedTest
    @ValueSource(strings = {"found", "instrumental"})
    void preservesSavedFallbackWhenBothProvidersHaveNoNewResult(String status) {
        saveLyrics(LRCLIB_SOURCE, status);
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("not_found"));
        when(fallback.find("Artist", "Title", "Album", 123.0))
                .thenReturn(LrclibClient.Result.unresolved("unavailable"));

        var result = service.get(1);

        assertThat(result.lyricsStatus()).isEqualTo(status);
        assertThat(result.lyricsSource()).isEqualTo(LRCLIB_SOURCE);
        assertThat(result.lyrics()).isEqualTo("found".equals(status) ? "Saved text" : null);
    }

    @Test
    void cachedFallbackRetriesRpAfterOneDayAndPrefersItsLyrics() {
        saveLyrics(LRCLIB_SOURCE, "found");
        song.setMetadataCheckedAt(Instant.now());
        assertThat(service.get(1).lyrics()).isEqualTo("Saved text");
        verifyNoInteractions(lyrics, fallback);

        song.setMetadataCheckedAt(Instant.now().minus(Duration.ofDays(1)).minusSeconds(1));
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(rp("found"));

        assertThat(service.get(1).lyrics()).isEqualTo("RP text");
        assertThat(song.getLyricsSource()).isEqualTo(RP_SOURCE);
        verifyNoInteractions(fallback);
    }

    @Test
    void missingSongReturns404WithoutCallingProviders() {
        assertThatThrownBy(() -> service.get(2)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.get(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lyrics, fallback);
    }

    private void saveLyrics(String source, String status) {
        song.setLyrics("instrumental".equals(status) ? null : "Saved text");
        song.setLyricsStatus(status);
        song.setLyricsSource(source);
        song.setMetadataCheckedAt(Instant.now().minus(Duration.ofDays(31)));
    }

    private LyricsClient.Result rp(String status) {
        return new LyricsClient.Result(status, "found".equals(status) ? "RP text" : null, RP_SOURCE, 123.0, 42L);
    }
}
