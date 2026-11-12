package com.radioparadise.tracker.dto;

import com.radioparadise.tracker.model.PlaybackEvent;
import com.radioparadise.tracker.model.Song;
import java.time.Instant;

public record PlaybackRecord(Long id, Long songId, Instant playedAt, String channel,
                             String title, String artist, String album, String year, String coverUrl,
                             String timeSource) {
    public static PlaybackRecord from(PlaybackEvent event) {
        Song song = event.getSong();
        return new PlaybackRecord(event.getId(), song.getId(), event.getObservedAt(), event.getChannel(),
                song.getTitle(), song.getArtist(), song.getAlbum(), song.getYear(), song.getCoverUrl(),
                event.getRpSourceKey() == null ? "observed" : "broadcast");
    }
}
