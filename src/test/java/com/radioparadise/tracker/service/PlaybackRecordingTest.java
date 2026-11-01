package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import com.radioparadise.tracker.repository.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackRecordingTest {
    @Mock SongRepository songs;
    @Mock PlaybackEventRepository plays;
    SongService service;

    @BeforeEach
    void setUp() {
        service = new SongService(songs, plays);
    }

    @Test
    void firstObservationWritesMetadataAndAPlayWithAnAbsoluteTimestamp() {
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Instant before = Instant.now();
        Song song = service.saveOrUpdateSong(response("Track A"), "1");
        ArgumentCaptor<PlaybackEvent> event = ArgumentCaptor.forClass(PlaybackEvent.class);
        verify(plays).save(event.capture());
        assertThat(event.getValue().getSong()).isSameAs(song);
        assertThat(event.getValue().getChannel()).isEqualTo("1");
        assertThat(event.getValue().getObservedAt()).isBetween(before, Instant.now());
        assertThat(song.getTitle()).isEqualTo("Track A");
        assertThat(song.getPlayCount()).isEqualTo(1);
    }

    @Test
    void persistedLatestPlaySuppressesDuplicatesEvenInANewServiceInstance() {
        Song song = Song.builder().id(9L).channel("1").artist("Artist")
                .title("Track A").album("Album").playCount(7).build();
        when(plays.findFirstByChannelOrderByObservedAtDescIdDesc("1"))
                .thenReturn(Optional.of(PlaybackEvent.builder().song(song).channel("1").build()));
        assertThat(service.saveOrUpdateSong(response("Track A"), "1")).isSameAs(song);
        SongService restarted = new SongService(songs, plays);
        assertThat(restarted.saveOrUpdateSong(response("Track A"), "1")).isSameAs(song);
        assertThat(song.getPlayCount()).isEqualTo(7);
        verifyNoInteractions(songs);
        verify(plays, never()).save(any());
    }

    @Test
    void returningToATrackAfterAnotherTrackCreatesANewPlayAndIncrementsItsCount() {
        Song previous = Song.builder().title("Track B").artist("Artist").album("Album").build();
        when(plays.findFirstByChannelOrderByObservedAtDescIdDesc("2"))
                .thenReturn(Optional.of(PlaybackEvent.builder().song(previous).channel("2").build()));
        Song existing = Song.builder().id(42L).title("Track A").artist("Artist")
                .album("Album").channel("2").playCount(2).build();
        when(songs.findByArtistAndTitleAndAlbumAndChannel("Artist", "Track A", "Album", "2"))
                .thenReturn(Optional.of(existing));
        when(songs.save(existing)).thenReturn(existing);
        assertThat(service.saveOrUpdateSong(response("Track A"), "2").getPlayCount()).isEqualTo(3);
        verify(plays).save(any(PlaybackEvent.class));
    }

    @Test
    void databaseFailureIsPropagatedSoTheSchedulerCanRetry() {
        when(songs.save(any(Song.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(plays.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.saveOrUpdateSong(response("Track A"), "0"))
                .isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
    }

    private RadioParadiseResponse response(String title) {
        RadioParadiseResponse response = new RadioParadiseResponse();
        response.setArtist("Artist");
        response.setTitle(title);
        response.setAlbum("Album");
        response.setYear("2026");
        return response;
    }
}
