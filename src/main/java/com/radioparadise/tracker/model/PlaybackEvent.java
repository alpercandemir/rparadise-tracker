package com.radioparadise.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "playback_event", indexes = {
        @Index(name = "playback_channel_time_idx", columnList = "channel, observed_at DESC, id DESC"),
        @Index(name = "playback_time_idx", columnList = "observed_at DESC, id DESC"),
        @Index(name = "playback_song_idx", columnList = "song_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybackEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "song_id", nullable = false, updatable = false)
    private Song song;

    @Column(nullable = false, updatable = false)
    private String channel;

    // Keep the legacy column; rpSourceKey distinguishes broadcast time from observation time.
    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Column(name = "rp_source_key", length = 64, updatable = false)
    private String rpSourceKey;
}
