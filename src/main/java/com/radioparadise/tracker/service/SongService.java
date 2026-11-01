package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.repository.SongRepository;
import com.radioparadise.tracker.repository.PlaybackEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class SongService {

    private static final int DEFAULT_SONG_RESULT_LIMIT = 20;

    private final SongRepository songRepository;
    private final PlaybackEventRepository playbackEventRepository;

    @Transactional
    public Song saveOrUpdateSong(RadioParadiseResponse response, String channel) {
        Objects.requireNonNull(response, "response cannot be null");

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

        Song song = songRepository.findByArtistAndTitleAndAlbumAndChannel(
                response.getArtist(),
                response.getTitle(),
                response.getAlbum(),
                channel)
                .map(existingSong -> updateExistingSong(existingSong, channel))
                .orElseGet(() -> createNewSong(response, channel));
        playbackEventRepository.save(PlaybackEvent.builder()
                .song(song).channel(channel).observedAt(Instant.now()).build());
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

    private Song updateExistingSong(Song song, String channel) {
        song.setLastPlayed(LocalDateTime.now(ZoneId.of("Europe/Istanbul")));
        song.setPlayCount(song.getPlayCount() + 1);

        log.info("Updated existing song on channel {}: {} - {} (play count: {})",
                channel, song.getArtist(), song.getTitle(), song.getPlayCount());

        return songRepository.save(song);
    }

    private Song createNewSong(RadioParadiseResponse response, String channel) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Europe/Istanbul"));
        Song newSong = Song.builder()
                .channel(channel)
                .artist(response.getArtist())
                .title(response.getTitle())
                .album(response.getAlbum())
                .year(response.getYear())
                .coverUrl(response.getCover())
                .firstPlayed(now)
                .lastPlayed(now)
                .playCount(1)
                .build();

        log.info("Saved new song on channel {}: {} - {} ({})",
                channel, newSong.getArtist(), newSong.getTitle(), newSong.getAlbum());

        return songRepository.save(newSong);
    }
}
