package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class SongService {

    private static final int DEFAULT_SONG_RESULT_LIMIT = 20;

    private final SongRepository songRepository;

    @Transactional
    public Song saveOrUpdateSong(RadioParadiseResponse response, String channel) {
        Objects.requireNonNull(response, "response cannot be null");

        return songRepository.findByArtistAndTitleAndAlbumAndChannel(
                response.getArtist(),
                response.getTitle(),
                response.getAlbum(),
                channel)
                .map(existingSong -> updateExistingSong(existingSong, channel))
                .orElseGet(() -> createNewSong(response, channel));
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
        song.setLastPlayed(LocalDateTime.now());
        song.setPlayCount(song.getPlayCount() + 1);

        log.info("Updated existing song on channel {}: {} - {} (play count: {})",
                channel, song.getArtist(), song.getTitle(), song.getPlayCount());

        return songRepository.save(song);
    }

    private Song createNewSong(RadioParadiseResponse response, String channel) {
        LocalDateTime now = LocalDateTime.now();
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
