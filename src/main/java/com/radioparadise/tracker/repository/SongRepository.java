package com.radioparadise.tracker.repository;

import com.radioparadise.tracker.model.Song;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SongRepository extends JpaRepository<Song, Long> {

    Optional<Song> findByArtistAndTitleAndAlbumAndChannel(String artist, String title, String album, String channel);

    List<Song> findByChannel(String channel);

    List<Song> findTop20ByOrderByLastPlayedDesc();

    List<Song> findTop20ByOrderByPlayCountDesc();
}
