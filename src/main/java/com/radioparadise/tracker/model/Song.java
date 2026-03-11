package com.radioparadise.tracker.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
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

    @Column(name = "first_played", nullable = false)
    private LocalDateTime firstPlayed;

    @Column(name = "last_played", nullable = false)
    private LocalDateTime lastPlayed;

    @Column(name = "play_count", nullable = false)
    @Builder.Default
    private Integer playCount = 1;

    @PrePersist
    protected void onCreate() {
        if (firstPlayed == null) {
            firstPlayed = LocalDateTime.now();
        }
        if (lastPlayed == null) {
            lastPlayed = LocalDateTime.now();
        }
        if (playCount == null) {
            playCount = 1;
        }
    }
}
