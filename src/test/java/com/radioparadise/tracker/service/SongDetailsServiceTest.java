package com.radioparadise.tracker.service;

import com.radioparadise.tracker.client.LyricsClient;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SongDetailsServiceTest {
    SongRepository songs = mock(SongRepository.class);
    LyricsClient lyrics = mock(LyricsClient.class);
    SongDetailsService service = new SongDetailsService(songs, lyrics);
    Song song;

    @BeforeEach void setup() {
        song = Song.builder().id(1L).rpSongId(42L).channel("1").artist("Artist").title("Title").album("Album").playCount(7).build();
        lenient().when(songs.findById(1L)).thenAnswer(call -> Optional.of(song));
        lenient().doAnswer(call -> {
            song.setLyrics(call.getArgument(1)); song.setLyricsStatus(call.getArgument(2));
            song.setLyricsSource(call.getArgument(3)); song.setDurationSeconds(call.getArgument(4));
            song.setMetadataCheckedAt(call.getArgument(5)); song.setRpSongId(call.getArgument(6)); return null;
        }).when(songs).updateDetails(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test void persistsLyricsOnceWithoutSavingOrChangingListeningStats() {
        when(lyrics.find(42L, "Artist", "Title", "Album", "1")).thenReturn(new LyricsClient.Result("found", "Text", LyricsClient.SOURCE + "/song/42", 123.0, 42L));
        assertThat(service.get(1).lyrics()).isEqualTo("Text");
        assertThat(service.get(1).song().playCount()).isEqualTo(7);
        verify(lyrics, times(1)).find(42L, "Artist", "Title", "Album", "1");
        verify(songs, never()).save(any());
        verify(songs, times(1)).updateDetails(eq(1L), eq("Text"), eq("found"), eq(LyricsClient.SOURCE + "/song/42"), eq(123.0), any(), eq(42L));
    }

    @Test void cachesMissingResultsAcrossRequestsAndRetriesAfterOneDay() {
        when(lyrics.find(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(new LyricsClient.Result("not_found", null, LyricsClient.SOURCE + "/song/42", null, 42L));
        service.get(1); service.get(1);
        verify(lyrics, times(1)).find(any(), anyString(), anyString(), anyString(), anyString());
        song.setMetadataCheckedAt(Instant.now().minus(Duration.ofDays(2)));
        service.get(1);
        verify(lyrics, times(2)).find(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void providerFailureIsRetriedAfterFiveMinutes() {
        song.setLyricsStatus("unavailable");
        song.setLyricsSource(LyricsClient.SOURCE + "/song/42");
        song.setMetadataCheckedAt(Instant.now().minusSeconds(60));
        service.get(1);
        verifyNoInteractions(lyrics);
        song.setMetadataCheckedAt(Instant.now().minusSeconds(301));
        when(lyrics.find(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(new LyricsClient.Result("unavailable", null, LyricsClient.SOURCE + "/song/42", null, 42L));
        assertThat(service.get(1).lyricsStatus()).isEqualTo("unavailable");
        verify(lyrics).find(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void temporaryFailurePreservesPreviouslySavedLyrics() {
        song.setLyrics("Saved text"); song.setLyricsStatus("found"); song.setLyricsSource(LyricsClient.SOURCE + "/song/42");
        song.setMetadataCheckedAt(Instant.now().minus(Duration.ofDays(31)));
        when(lyrics.find(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(new LyricsClient.Result("unavailable", null, LyricsClient.SOURCE + "/song/42", null, 42L));
        assertThat(service.get(1).lyrics()).isEqualTo("Saved text");
    }

    @Test void replacesPreviousProviderResultsImmediately() {
        song.setLyrics("Old text"); song.setLyricsStatus("found"); song.setLyricsSource("https://lrclib.net/api/get/1");
        song.setMetadataCheckedAt(Instant.now());
        when(lyrics.find(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new LyricsClient.Result("found", "RP text", LyricsClient.SOURCE + "/song/42", 123.0, 42L));
        assertThat(service.get(1).lyrics()).isEqualTo("RP text");
        assertThat(song.getLyricsSource()).isEqualTo(LyricsClient.SOURCE + "/song/42");
    }

    @Test void doesNotRetainExternalLyricsWhenRpIsUnavailable() {
        song.setLyrics("Old text"); song.setLyricsStatus("found"); song.setLyricsSource("https://lrclib.net/api/get/1");
        song.setMetadataCheckedAt(Instant.now());
        when(lyrics.find(any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new LyricsClient.Result("unavailable", null, LyricsClient.SOURCE + "/song/42", null, 42L));
        assertThat(service.get(1).lyrics()).isNull();
        assertThat(song.getLyricsStatus()).isEqualTo("unavailable");
    }

    @Test void missingSongReturns404WithoutCallingProvider() {
        assertThatThrownBy(() -> service.get(2)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.get(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lyrics);
    }
}
