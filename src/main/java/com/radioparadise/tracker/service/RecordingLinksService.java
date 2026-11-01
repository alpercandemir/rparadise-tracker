package com.radioparadise.tracker.service;

import com.radioparadise.tracker.client.MusicBrainzClient;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class RecordingLinksService {
    private final SongRepository songs;
    private final MusicBrainzClient catalog;

    public RecordingLinksService(SongRepository songs, MusicBrainzClient catalog) {
        this.songs = songs;
        this.catalog = catalog;
    }

    // Called under the song-details lock, after RP duration enrichment, outside playback transactions.
    public void refresh(Song song) {
        if (!needsRefresh(song)) {
            return;
        }
        var result = catalog.find(song.getArtist(), song.getTitle(), song.getAlbum(), song.getDurationSeconds());
        boolean preserveMatch = "unavailable".equals(result.status()) && song.getRecordingMatchSource() != null;
        songs.updateRecordingLinks(song.getId(), preserveMatch ? song.getIsrc() : result.isrc(), result.status(),
                preserveMatch ? song.getRecordingMatchSource() : result.source(),
                preserveMatch ? song.getSpotifyUrl() : result.spotifyUrl(),
                preserveMatch ? song.getYoutubeMusicUrl() : result.youtubeMusicUrl(), Instant.now());
    }

    private boolean needsRefresh(Song song) {
        if (song.getRecordingCheckedAt() == null || song.getRecordingMatchStatus() == null) {
            return true;
        }
        if ("insufficient_metadata".equals(song.getRecordingMatchStatus())
                && song.getDurationSeconds() != null && song.getDurationSeconds() > 0) {
            return true;
        }
        Duration ttl = switch (song.getRecordingMatchStatus()) {
            case "matched" -> Duration.ofDays(30);
            case "not_found", "ambiguous", "insufficient_metadata" -> Duration.ofDays(1);
            default -> Duration.ofMinutes(5);
        };
        return !song.getRecordingCheckedAt().plus(ttl).isAfter(Instant.now());
    }
}
