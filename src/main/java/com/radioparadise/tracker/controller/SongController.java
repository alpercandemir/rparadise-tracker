package com.radioparadise.tracker.controller;

import com.radioparadise.tracker.dto.SongDetails;
import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.model.RadioParadiseChannel;
import com.radioparadise.tracker.model.Song;
import com.radioparadise.tracker.service.ExpiringCache;
import com.radioparadise.tracker.service.SongDetailsService;
import com.radioparadise.tracker.service.SongService;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
public class SongController {

    private final SongService songService;
    private final SongDetailsService songDetailsService;
    private final ExpiringCache<String, List<TrackRecord>> songLists =
            new ExpiringCache<>(7, Duration.ofSeconds(30));

    public void clearCache() {
        songLists.clear();
    }

    @GetMapping("/current")
    public ResponseEntity<List<TrackRecord>> getCurrentSongs() {
        return cachedSongs("current", songService::getCurrentSongs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SongDetails> getSong(@PathVariable long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(songDetailsService.get(id));
    }

    @GetMapping
    public ResponseEntity<List<TrackRecord>> getAllSongs() {
        return cachedSongs("all", songService::getAllSongs);
    }

    @GetMapping("/recent")
    public ResponseEntity<List<TrackRecord>> getRecentSongs() {
        return cachedSongs("recent", songService::getRecentSongs);
    }

    @GetMapping("/most-played")
    public ResponseEntity<List<TrackRecord>> getMostPlayedSongs() {
        return cachedSongs("most-played", songService::getMostPlayedSongs);
    }

    @GetMapping("/channel/{channelId}")
    public ResponseEntity<List<TrackRecord>> getSongsByChannel(@PathVariable String channelId) {
        String validatedChannelId = RadioParadiseChannel.fromId(channelId).id();
        return cachedSongs(
                "channel:" + validatedChannelId,
                () -> songService.getSongsByChannel(validatedChannelId));
    }

    private ResponseEntity<List<TrackRecord>> cachedSongs(
            String key, Supplier<List<Song>> loadSongs) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        songLists.get(
                                key,
                                () -> loadSongs.get().stream().map(TrackRecord::from).toList()));
    }
}
