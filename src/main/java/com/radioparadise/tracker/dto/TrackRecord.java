package com.radioparadise.tracker.dto;

import com.radioparadise.tracker.model.Song;
import java.time.Instant;
import java.time.ZoneId;

public record TrackRecord(Long id, String channel, String title, String artist, String album,
                          String year, String coverUrl, Instant firstPlayed,
                          Instant lastPlayed, Integer playCount) {
    public static TrackRecord from(Song song) {
        // Legacy columns contain Istanbul wall time, not UTC. Preserve their actual instant.
        ZoneId zone = ZoneId.of("Europe/Istanbul");
        return new TrackRecord(song.getId(), song.getChannel(), song.getTitle(), song.getArtist(),
                song.getAlbum(), song.getYear(), song.getCoverUrl(),
                song.getFirstPlayed().atZone(zone).toInstant(),
                song.getLastPlayed().atZone(zone).toInstant(), song.getPlayCount());
    }
}
