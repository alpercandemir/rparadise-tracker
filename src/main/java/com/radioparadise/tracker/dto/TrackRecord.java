package com.radioparadise.tracker.dto;

import com.radioparadise.tracker.model.Song;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record TrackRecord(Long id, String channel, String title, String artist, String album,
                          String year, String coverUrl, OffsetDateTime firstPlayed,
                          OffsetDateTime lastPlayed, Integer playCount) {
    public static TrackRecord from(Song song) {
        ZoneId zone = ZoneId.of("Europe/Istanbul");
        return new TrackRecord(song.getId(), song.getChannel(), song.getTitle(), song.getArtist(),
                song.getAlbum(), song.getYear(), song.getCoverUrl(),
                song.getFirstPlayed().atZone(zone).toOffsetDateTime(),
                song.getLastPlayed().atZone(zone).toOffsetDateTime(), song.getPlayCount());
    }
}
