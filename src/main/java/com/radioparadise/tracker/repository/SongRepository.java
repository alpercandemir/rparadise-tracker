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
            + "s.durationSeconds = :duration, s.metadataCheckedAt = :checkedAt, s.rpSongId = coalesce(:rpSongId, s.rpSongId) where s.id = :id")
    void updateDetails(Long id, String lyrics, String status, String source, Double duration, Instant checkedAt, Long rpSongId);

    @Modifying
    @Transactional
    @Query("update Song s set s.isrc = :isrc, s.recordingMatchStatus = :status, "
            + "s.recordingMatchSource = :source, s.spotifyUrl = :spotifyUrl, "
            + "s.youtubeMusicUrl = :youtubeMusicUrl, s.recordingCheckedAt = :checkedAt where s.id = :id")
    void updateRecordingLinks(Long id, String isrc, String status, String source,
                              String spotifyUrl, String youtubeMusicUrl, Instant checkedAt);

    @Query("select coalesce(max(s.id), 0) from Song s")
    long findHighestId();

    @Query("select s.id from Song s where s.isrc is null and s.id > :afterId "
            + "and s.id <= :throughId order by s.id")
    List<Long> findIdsWithoutIsrc(long afterId, long throughId, Pageable pageable);

    @Query("select s.id from Song s where s.recordingMatchStatus = 'unavailable' "
            + "and s.id > :afterId and s.id <= :throughId order by s.id")
    List<Long> findIdsWithRecordingErrors(long afterId, long throughId, Pageable pageable);

    Optional<Song> findByArtistAndTitleAndAlbumAndChannel(String artist, String title, String album, String channel);

    List<Song> findByChannel(String channel);

    Optional<Song> findFirstByChannelOrderByLastPlayedDescIdDesc(String channel);

    List<Song> findAllByOrderByLastPlayedDesc(Pageable pageable);

    List<Song> findAllByOrderByLastPlayedDescIdDesc(Pageable pageable);

    List<Song> findAllByOrderByPlayCountDesc(Pageable pageable);

    @Query("select s.channel as channel, s.year as year, count(s) as trackCount "
            + "from Song s group by s.channel, s.year")
    List<TrackCount> countTracksByChannelAndYear();

    interface TrackCount {
        String getChannel();
        String getYear();
        long getTrackCount();
    }
}
