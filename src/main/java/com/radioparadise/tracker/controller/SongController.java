package com.radioparadise.tracker.controller;

import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.model.RadioParadiseChannel;
import com.radioparadise.tracker.service.SongService;
import com.radioparadise.tracker.service.SongDetailsService;
import com.radioparadise.tracker.service.ExpiringCache;
import com.radioparadise.tracker.dto.SongDetails;
import org.springframework.http.CacheControl;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/songs")
@RequiredArgsConstructor
public class SongController {

    private final SongService songService;
    private final SongDetailsService details;
    private final ExpiringCache<String, List<TrackRecord>> cache = new ExpiringCache<>(7, Duration.ofSeconds(30));

    public void clearCache() { cache.clear(); }

    @GetMapping("/current")
    public ResponseEntity<List<TrackRecord>> getCurrentSongs() {
        return cached("current", songService::getCurrentSongs);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SongDetails> getSong(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(details.get(id));
    }

    @GetMapping
    public ResponseEntity<List<TrackRecord>> getAllSongs() {
        return cached("all", () -> songService.getAllSongs());
    }

    @GetMapping("/recent")
    public ResponseEntity<List<TrackRecord>> getRecentSongs() {
        return cached("recent", () -> songService.getRecentSongs());
    }

    @GetMapping("/most-played")
    public ResponseEntity<List<TrackRecord>> getMostPlayedSongs() {
        return cached("most-played", () -> songService.getMostPlayedSongs());
    }

    @GetMapping("/channel/{channelId}")
    public ResponseEntity<List<TrackRecord>> getSongsByChannel(@PathVariable String channelId) {
        String validatedChannelId = RadioParadiseChannel.fromId(channelId).id();
        return cached("channel:" + validatedChannelId, () -> songService.getSongsByChannel(validatedChannelId));
    }

    private ResponseEntity<List<TrackRecord>> cached(String key,
            java.util.function.Supplier<List<com.radioparadise.tracker.model.Song>> load) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(cache.get(key, () -> load.get().stream().map(TrackRecord::from).toList()));
    }
}
