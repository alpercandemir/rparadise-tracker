package com.radioparadise.tracker.service;

import com.radioparadise.tracker.client.LyricsClient;
import com.radioparadise.tracker.dto.SongDetails;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.repository.SongRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;

@Service
public class SongDetailsService {
    private final SongRepository songs;
    private final LyricsClient lyrics;
    private final RecordingLinksService recordingLinks;
    private final Object[] locks = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public SongDetailsService(SongRepository songs, LyricsClient lyrics, RecordingLinksService recordingLinks) {
        this.songs = songs;
        this.lyrics = lyrics;
        this.recordingLinks = recordingLinks;
    }

    public SongDetails get(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("Song ID must be positive");
        }
        synchronized (locks[(int) (id % locks.length)]) {
            Song song = songs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song not found"));
            if (needsRefresh(song)) {
                var result = lyrics.find(song.getRpSongId(), song.getArtist(), song.getTitle(), song.getAlbum(), song.getChannel());
                // Preserve a previously successful lookup when the provider has a temporary failure.
                boolean hasSavedRpLyrics = LyricsClient.isRadioParadiseSource(song.getLyricsSource())
                        && ("found".equals(song.getLyricsStatus()) || "instrumental".equals(song.getLyricsStatus()));
                boolean preserve = result.status().equals("unavailable") && hasSavedRpLyrics;
                Instant checkedAt = Instant.now();
                songs.updateDetails(id, preserve ? song.getLyrics() : result.lyrics(),
                        preserve ? song.getLyricsStatus() : result.status(),
                        preserve ? song.getLyricsSource() : result.source(),
                        result.duration() == null ? song.getDurationSeconds() : result.duration(), checkedAt, result.rpSongId());
                song = songs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            }
            recordingLinks.refresh(song);
            song = songs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            return SongDetails.from(song);
        }
    }

    private boolean needsRefresh(Song song) {
        if (!LyricsClient.isRadioParadiseSource(song.getLyricsSource())
                || song.getMetadataCheckedAt() == null || song.getLyricsStatus() == null) {
            return true;
        }
        // A legacy row may have gained its RP ID since an earlier unresolved lookup.
        if (song.getRpSongId() != null && LyricsClient.SOURCE.equals(song.getLyricsSource())) {
            return true;
        }
        Duration ttl = switch (song.getLyricsStatus()) {
            case "found", "instrumental" -> Duration.ofDays(30);
            case "not_found" -> Duration.ofDays(1);
            default -> Duration.ofMinutes(5);
        };
        return !song.getMetadataCheckedAt().plus(ttl).isAfter(Instant.now());
    }
}
