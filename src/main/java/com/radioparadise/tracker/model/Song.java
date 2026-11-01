package com.radioparadise.tracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "song", uniqueConstraints = @UniqueConstraint(columnNames = { "artist", "title", "album", "channel" }))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Song {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String artist;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String album;

    @Column(name = "release_year")
    private String year;

    @Column(length = 500)
    private String coverUrl;

    @Column(length = 500)
    private String coverMediumUrl;

    @Column(length = 500)
    private String coverSmallUrl;

    @Column(columnDefinition = "text")
    private String lyrics;

    private Long rpSongId;

    private String lyricsStatus;

    @Column(length = 500)
    private String lyricsSource;

    private Double durationSeconds;

    private Instant metadataCheckedAt;

    @Column(name = "first_played", nullable = false)
    private Instant firstPlayed;

    @Column(name = "last_played", nullable = false)
    private Instant lastPlayed;

    @Column(name = "play_count", nullable = false)
    @Builder.Default
    private Integer playCount = 1;

    @PrePersist
    protected void onCreate() {
        if (firstPlayed == null) {
            firstPlayed = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
        if (lastPlayed == null) {
            lastPlayed = firstPlayed;
        }
        if (playCount == null) {
            playCount = 1;
        }
    }
}
