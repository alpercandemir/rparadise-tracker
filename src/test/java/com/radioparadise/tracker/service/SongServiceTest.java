package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SongServiceTest {

    @Mock
    private SongRepository songRepository;

    @Mock
    private PlaybackEventRepository playbackEventRepository;

    private SongService songService;

    @BeforeEach
    void setUp() {
        songService = new SongService(songRepository, playbackEventRepository);
    }

    @Test
    void saveOrUpdateSongCreatesNewSongWithResponseMetadataAndInitialPlayTimes() {
        RadioParadiseResponse response = response();
        when(songRepository.findByArtistAndTitleAndAlbumAndChannel(
                "Artist", "Title", "Album", "mellow")).thenReturn(Optional.empty());
        Song persistedSong = Song.builder().id(42L).build();
        when(songRepository.save(any(Song.class))).thenReturn(persistedSong);
        Instant beforeSave = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Song result = songService.saveOrUpdateSong(response, "mellow");

        Instant afterSave = Instant.now();
        ArgumentCaptor<Song> captor = ArgumentCaptor.forClass(Song.class);
        verify(songRepository).save(captor.capture());
        Song newSong = captor.getValue();
        assertThat(newSong.getId()).isNull();
        assertThat(newSong.getChannel()).isEqualTo("mellow");
        assertThat(newSong.getArtist()).isEqualTo("Artist");
        assertThat(newSong.getTitle()).isEqualTo("Title");
        assertThat(newSong.getAlbum()).isEqualTo("Album");
        assertThat(newSong.getYear()).isEqualTo("2020");
        assertThat(newSong.getCoverUrl()).isEqualTo("https://example.com/cover.jpg");
        assertThat(newSong.getPlayCount()).isEqualTo(1);
        assertThat(newSong.getFirstPlayed()).isBetween(beforeSave, afterSave);
        assertThat(newSong.getLastPlayed()).isEqualTo(newSong.getFirstPlayed());
        assertThat(result).isSameAs(persistedSong);
    }

    @Test
    void saveOrUpdateSongIncrementsExistingSongPlayCountAndUpdatesLastPlayed() {
        Instant firstPlayed = Instant.parse("2025-01-01T09:00:00Z");
        Song existingSong = Song.builder()
                .id(42L)
                .channel("mellow")
                .artist("Artist")
                .title("Title")
                .album("Album")
                .year("1999")
                .coverUrl("https://example.com/original.jpg")
                .firstPlayed(firstPlayed)
                .lastPlayed(firstPlayed.plusSeconds(86400))
                .playCount(3)
                .build();
        when(songRepository.findByArtistAndTitleAndAlbumAndChannel(
                "Artist", "Title", "Album", "mellow")).thenReturn(Optional.of(existingSong));
        Song persistedSong = Song.builder().id(42L).build();
        when(songRepository.save(existingSong)).thenReturn(persistedSong);
        Instant beforeSave = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Song result = songService.saveOrUpdateSong(response(), "mellow");

        Instant afterSave = Instant.now();
        verify(songRepository).save(existingSong);
        assertThat(existingSong.getPlayCount()).isEqualTo(4);
        assertThat(existingSong.getLastPlayed()).isBetween(beforeSave, afterSave);
        assertThat(existingSong.getFirstPlayed()).isEqualTo(firstPlayed);
        assertThat(existingSong.getId()).isEqualTo(42L);
        assertThat(existingSong.getChannel()).isEqualTo("mellow");
        assertThat(existingSong.getArtist()).isEqualTo("Artist");
        assertThat(existingSong.getTitle()).isEqualTo("Title");
        assertThat(existingSong.getAlbum()).isEqualTo("Album");
        assertThat(existingSong.getYear()).isEqualTo("1999");
        assertThat(existingSong.getCoverUrl()).isEqualTo("https://example.com/original.jpg");
        assertThat(result).isSameAs(persistedSong);
    }

    @Test
    void saveOrUpdateSongRejectsNullResponseBeforeAccessingRepository() {
        assertThatNullPointerException()
                .isThrownBy(() -> songService.saveOrUpdateSong(null, "mellow"))
                .withMessage("response cannot be null");

        verifyNoInteractions(songRepository);
    }

    @Test
    void getAllSongsReturnsRepositoryResults() {
        List<Song> songs = List.of(Song.builder().id(1L).build(), Song.builder().id(2L).build());
        when(songRepository.findAll()).thenReturn(songs);

        assertThat(songService.getAllSongs()).isEqualTo(songs);

        verify(songRepository).findAll();
    }

    @Test
    void getRecentSongsRequestsFirstTwentySongsOrderedByLastPlayed() {
        List<Song> songs = List.of(Song.builder().id(2L).build(), Song.builder().id(1L).build());
        PageRequest page = PageRequest.of(0, 20);
        when(songRepository.findAllByOrderByLastPlayedDesc(page)).thenReturn(songs);

        assertThat(songService.getRecentSongs()).isEqualTo(songs);

        verify(songRepository).findAllByOrderByLastPlayedDesc(page);
    }

    @Test
    void getMostPlayedSongsRequestsFirstTwentySongsOrderedByPlayCount() {
        List<Song> songs = List.of(Song.builder().id(3L).build(), Song.builder().id(1L).build());
        PageRequest page = PageRequest.of(0, 20);
        when(songRepository.findAllByOrderByPlayCountDesc(page)).thenReturn(songs);

        assertThat(songService.getMostPlayedSongs()).isEqualTo(songs);

        verify(songRepository).findAllByOrderByPlayCountDesc(page);
    }

    @Test
    void getSongsByChannelReturnsSongsForRequestedChannel() {
        List<Song> songs = List.of(Song.builder().channel("rock").build());
        when(songRepository.findByChannel("rock")).thenReturn(songs);

        assertThat(songService.getSongsByChannel("rock")).isEqualTo(songs);

        verify(songRepository).findByChannel("rock");
    }

    private RadioParadiseResponse response() {
        RadioParadiseResponse response = new RadioParadiseResponse();
        response.setArtist("Artist");
        response.setTitle("Title");
        response.setAlbum("Album");
        response.setYear("2020");
        response.setCover("https://example.com/cover.jpg");
        return response;
    }
}
