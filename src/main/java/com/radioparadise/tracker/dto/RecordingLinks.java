package com.radioparadise.tracker.dto;

import com.radioparadise.tracker.model.Song;
import java.time.Instant;

public record RecordingLinks(String isrc, String status, String source, String spotifyUrl,
                             String youtubeMusicUrl, Instant checkedAt) {
    public static RecordingLinks from(Song song) {
        return new RecordingLinks(song.getIsrc(), song.getRecordingMatchStatus(), song.getRecordingMatchSource(),
                song.getSpotifyUrl(), song.getYoutubeMusicUrl(), song.getRecordingCheckedAt());
    }
}
