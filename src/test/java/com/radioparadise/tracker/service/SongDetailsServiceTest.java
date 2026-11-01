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
        song = Song.builder().id(1L).artist("Artist").title("Title").album("Album").playCount(7).build();
        lenient().when(songs.findById(1L)).thenAnswer(call -> Optional.of(song));
        lenient().doAnswer(call -> {
            song.setLyrics(call.getArgument(1)); song.setLyricsStatus(call.getArgument(2));
            song.setLyricsSource(call.getArgument(3)); song.setDurationSeconds(call.getArgument(4));
            song.setMetadataCheckedAt(call.getArgument(5)); return null;
        }).when(songs).updateDetails(anyLong(), any(), any(), any(), any(), any());
    }

    @Test void persistsLyricsOnceWithoutSavingOrChangingListeningStats() {
        when(lyrics.find("Artist", "Title", "Album")).thenReturn(new LyricsClient.Result("found", "Text", "source", 123.0));
        assertThat(service.get(1).lyrics()).isEqualTo("Text");
        assertThat(service.get(1).song().playCount()).isEqualTo(7);
        verify(lyrics, times(1)).find("Artist", "Title", "Album");
        verify(songs, never()).save(any());
        verify(songs, times(1)).updateDetails(eq(1L), eq("Text"), eq("found"), eq("source"), eq(123.0), any());
    }

    @Test void cachesMissingResultsAcrossRequestsAndRetriesAfterOneDay() {
        when(lyrics.find(anyString(), anyString(), anyString())).thenReturn(new LyricsClient.Result("not_found", null, null, null));
        service.get(1); service.get(1);
        verify(lyrics, times(1)).find(anyString(), anyString(), anyString());
        song.setMetadataCheckedAt(Instant.now().minus(Duration.ofDays(2)));
        service.get(1);
        verify(lyrics, times(2)).find(anyString(), anyString(), anyString());
    }

    @Test void providerFailureIsRetriedAfterFiveMinutes() {
        song.setLyricsStatus("unavailable");
        song.setMetadataCheckedAt(Instant.now().minusSeconds(60));
        service.get(1);
        verifyNoInteractions(lyrics);
        song.setMetadataCheckedAt(Instant.now().minusSeconds(301));
        when(lyrics.find(anyString(), anyString(), anyString())).thenReturn(new LyricsClient.Result("unavailable", null, null, null));
        assertThat(service.get(1).lyricsStatus()).isEqualTo("unavailable");
        verify(lyrics).find(anyString(), anyString(), anyString());
    }

    @Test void temporaryFailurePreservesPreviouslySavedLyrics() {
        song.setLyrics("Saved text"); song.setLyricsStatus("found"); song.setLyricsSource("source");
        song.setMetadataCheckedAt(Instant.now().minus(Duration.ofDays(31)));
        when(lyrics.find(anyString(), anyString(), anyString())).thenReturn(new LyricsClient.Result("unavailable", null, null, null));
        assertThat(service.get(1).lyrics()).isEqualTo("Saved text");
    }

    @Test void missingSongReturns404WithoutCallingProvider() {
        assertThatThrownBy(() -> service.get(2)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.get(-1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(lyrics);
    }
}
