package com.radioparadise.tracker.repository;

import com.radioparadise.tracker.model.Song;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SongRepository extends JpaRepository<Song, Long>, JpaSpecificationExecutor<Song> {

    @Modifying
    @Transactional
    @Query("update Song s set s.lyrics = :lyrics, s.lyricsStatus = :status, s.lyricsSource = :source, "
            + "s.durationSeconds = :duration, s.metadataCheckedAt = :checkedAt where s.id = :id")
    void updateDetails(Long id, String lyrics, String status, String source, Double duration, Instant checkedAt);

    Optional<Song> findByArtistAndTitleAndAlbumAndChannel(String artist, String title, String album, String channel);

    List<Song> findByChannel(String channel);

    Optional<Song> findFirstByChannelOrderByLastPlayedDescIdDesc(String channel);

    List<Song> findAllByOrderByLastPlayedDesc(Pageable pageable);

    List<Song> findAllByOrderByPlayCountDesc(Pageable pageable);
}
