package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.repository.SongRepository;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SongService {

    private static final int DEFAULT_SONG_RESULT_LIMIT = 20;

    private final SongRepository songRepository;
    private final PlaybackEventRepository playbackEventRepository;

    @Transactional
    public Song saveOrUpdateSong(RadioParadiseResponse response, String channel) {
        Objects.requireNonNull(response, "response cannot be null");
        requireMetadata("artist", response.getArtist());
        requireMetadata("title", response.getTitle());
        requireMetadata("album", response.getAlbum());

        // Durable checkpoint: a restart must not count the same ongoing play twice.
        var latest = playbackEventRepository.findFirstByChannelOrderByObservedAtDescIdDesc(channel);
        if (latest.isPresent()) {
            Song current = latest.get().getSong();
            if (Objects.equals(current.getArtist(), response.getArtist())
                    && Objects.equals(current.getTitle(), response.getTitle())
                    && Objects.equals(current.getAlbum(), response.getAlbum())) {
                return current;
            }
        }

        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Song song = songRepository.findByArtistAndTitleAndAlbumAndChannel(
                response.getArtist(),
                response.getTitle(),
                response.getAlbum(),
                channel)
                .map(existingSong -> updateExistingSong(existingSong, observedAt))
                .orElseGet(() -> createNewSong(response, channel, observedAt));
        playbackEventRepository.save(PlaybackEvent.builder()
                .song(song).channel(channel).observedAt(observedAt).build());
        return song;
    }

    public List<Song> getAllSongs() {
        return songRepository.findAll();
    }

    public List<Song> getRecentSongs() {
        return songRepository.findAllByOrderByLastPlayedDesc(PageRequest.of(0, DEFAULT_SONG_RESULT_LIMIT));
    }

    public List<Song> getMostPlayedSongs() {
        return songRepository.findAllByOrderByPlayCountDesc(PageRequest.of(0, DEFAULT_SONG_RESULT_LIMIT));
    }

    public List<Song> getSongsByChannel(String channel) {
        return songRepository.findByChannel(channel);
    }

    private static void requireMetadata(String field, String value) {
        if (!StringUtils.hasText(value)) {
            throw new InvalidSongMetadataException(field);
        }
    }

    private Song updateExistingSong(Song song, Instant observedAt) {
        song.setLastPlayed(observedAt);
        song.setPlayCount(song.getPlayCount() + 1);

        return songRepository.save(song);
    }

    private Song createNewSong(RadioParadiseResponse response, String channel, Instant observedAt) {
        Song newSong = Song.builder()
                .channel(channel)
                .artist(response.getArtist())
                .title(response.getTitle())
                .album(response.getAlbum())
                .year(response.getYear())
                .coverUrl(response.getCover())
                .firstPlayed(observedAt)
                .lastPlayed(observedAt)
                .playCount(1)
                .build();

        return songRepository.save(newSong);
    }
}
