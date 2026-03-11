package com.radioparadise.tracker.service;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SongService {

    private final SongRepository songRepository;

    @Transactional
    public Song saveOrUpdateSong(RadioParadiseResponse response, String channel) {
        if (response == null) {
            return null;
        }

        Optional<Song> existingSong = songRepository.findByArtistAndTitleAndAlbumAndChannel(
                response.getArtist(),
                response.getTitle(),
                response.getAlbum(),
                channel);

        if (existingSong.isPresent()) {
            // Song already exists on this channel, increment play count
            Song song = existingSong.get();
            song.setLastPlayed(LocalDateTime.now());
            song.setPlayCount(song.getPlayCount() + 1);

            log.info("Updated existing song on channel {}: {} - {} (Play count: {})",
                    channel, song.getArtist(), song.getTitle(), song.getPlayCount());

            return songRepository.save(song);
        } else {
            // New song on this channel, save it
            Song newSong = Song.builder()
                    .channel(channel)
                    .artist(response.getArtist())
                    .title(response.getTitle())
                    .album(response.getAlbum())
                    .year(response.getYear())
                    .coverUrl(response.getCover())
                    .firstPlayed(LocalDateTime.now())
                    .lastPlayed(LocalDateTime.now())
                    .playCount(1)
                    .build();

            log.info("Saved new song on channel {}: {} - {} ({})",
                    channel, newSong.getArtist(), newSong.getTitle(), newSong.getAlbum());

            return songRepository.save(newSong);
        }
    }

    public List<Song> getAllSongs() {
        return songRepository.findAll();
    }

    public List<Song> getRecentSongs() {
        return songRepository.findTop20ByOrderByLastPlayedDesc();
    }

    public List<Song> getMostPlayedSongs() {
        return songRepository.findTop20ByOrderByPlayCountDesc();
    }

    public List<Song> getSongsByChannel(String channel) {
        return songRepository.findByChannel(channel);
    }
}
