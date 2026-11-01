package com.radioparadise.tracker.dto;

import com.radioparadise.tracker.model.Song;
import java.time.Instant;

public record SongDetails(TrackRecord song, String coverMediumUrl, String coverSmallUrl,
                          Double durationSeconds, String lyrics, String lyricsStatus,
                          String lyricsSource, Instant metadataCheckedAt, RecordingLinks recordingLinks) {
    public static SongDetails from(Song song) {
        return new SongDetails(TrackRecord.from(song), song.getCoverMediumUrl(), song.getCoverSmallUrl(),
                song.getDurationSeconds(), song.getLyrics(), song.getLyricsStatus(),
                song.getLyricsSource(), song.getMetadataCheckedAt(), RecordingLinks.from(song));
    }
}
