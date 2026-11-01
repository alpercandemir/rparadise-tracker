package com.radioparadise.tracker.service;

import com.radioparadise.tracker.client.LyricsClient;
import com.radioparadise.tracker.client.LrclibClient;
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
    private final LrclibClient fallbackLyrics;
    private final RecordingLinksService recordingLinks;
    private final Object[] locks = IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    public SongDetailsService(SongRepository songs, LyricsClient lyrics, LrclibClient fallbackLyrics,
                              RecordingLinksService recordingLinks) {
        this.songs = songs;
        this.lyrics = lyrics;
        this.fallbackLyrics = fallbackLyrics;
        this.recordingLinks = recordingLinks;
    }

    public SongDetails get(long id) {
        if (id < 1) {
            throw new IllegalArgumentException("Song ID must be positive");
        }
        synchronized (locks[(int) (id % locks.length)]) {
            Song song = songs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Song not found"));
            if (needsRefresh(song)) {
                var result = findLyrics(song);
                Instant checkedAt = Instant.now();
                songs.updateDetails(id, result.lyrics(), result.status(), result.source(),
                        result.duration() == null ? song.getDurationSeconds() : result.duration(), checkedAt, result.rpSongId());
                song = songs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            }
            recordingLinks.refresh(song);
            song = songs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            return SongDetails.from(song);
        }
    }

    private LyricsClient.Result findLyrics(Song song) {
        var rp = lyrics.find(song.getRpSongId(), song.getArtist(), song.getTitle(), song.getAlbum(), song.getChannel());
        if (isResolved(rp.status())) {
            return rp;
        }
        boolean hasSavedLyrics = isKnownSource(song.getLyricsSource()) && isResolved(song.getLyricsStatus());
        if ("unavailable".equals(rp.status()) && hasSavedLyrics) {
            return savedLyrics(song, rp);
        }
        Double duration = rp.duration() == null ? song.getDurationSeconds() : rp.duration();
        var fallback = fallbackLyrics.find(song.getArtist(), song.getTitle(), song.getAlbum(), duration);
        if (isResolved(fallback.status())) {
            // LRCLIB identifies lyrics only; RP IDs and recording duration remain authoritative.
            return new LyricsClient.Result(fallback.status(), fallback.lyrics(), fallback.source(), rp.duration(), rp.rpSongId());
        }
        if (hasSavedLyrics) {
            return savedLyrics(song, rp);
        }
        boolean unavailable = "unavailable".equals(rp.status()) || "unavailable".equals(fallback.status());
        return new LyricsClient.Result(unavailable ? "unavailable" : "not_found", null, rp.source(), rp.duration(), rp.rpSongId());
    }

    private static LyricsClient.Result savedLyrics(Song song, LyricsClient.Result rp) {
        return new LyricsClient.Result(song.getLyricsStatus(), song.getLyrics(), song.getLyricsSource(), rp.duration(), rp.rpSongId());
    }

    private static boolean isResolved(String status) {
        return "found".equals(status) || "instrumental".equals(status);
    }

    private static boolean isKnownSource(String source) {
        return LyricsClient.isRadioParadiseSource(source) || LrclibClient.isSource(source);
    }

    private boolean needsRefresh(Song song) {
        if (!isKnownSource(song.getLyricsSource())
                || song.getMetadataCheckedAt() == null || song.getLyricsStatus() == null) {
            return true;
        }
        if (LrclibClient.isSource(song.getLyricsSource())) {
            return !song.getMetadataCheckedAt().plus(Duration.ofDays(1)).isAfter(Instant.now());
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
