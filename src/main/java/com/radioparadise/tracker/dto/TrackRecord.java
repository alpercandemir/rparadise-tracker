package com.radioparadise.tracker.dto;

import com.radioparadise.tracker.model.Song;
import java.time.Instant;

public record TrackRecord(Long id, String channel, String title, String artist, String album,
                          String year, String coverUrl, Instant firstPlayed,
                          Instant lastPlayed, Integer playCount) {
    public static TrackRecord from(Song song) {
        return new TrackRecord(song.getId(), song.getChannel(), song.getTitle(), song.getArtist(),
                song.getAlbum(), song.getYear(), song.getCoverUrl(),
                song.getFirstPlayed(), song.getLastPlayed(), song.getPlayCount());
    }
}
