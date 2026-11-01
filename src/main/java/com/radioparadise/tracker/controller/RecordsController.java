package com.radioparadise.tracker.controller;

import com.radioparadise.tracker.dto.PlaybackRecord;
import com.radioparadise.tracker.dto.RecordsPage;
import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.service.ExpiringCache;
import com.radioparadise.tracker.service.RecordsQuery;
import com.radioparadise.tracker.service.RecordsService;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordsController {
    private final RecordsService recordsService;
    private final ExpiringCache<RecordsQuery, RecordsPage<TrackRecord>> trackCache =
            new ExpiringCache<>(128, Duration.ofSeconds(30));
    private final ExpiringCache<RecordsQuery, RecordsPage<PlaybackRecord>> playCache =
            new ExpiringCache<>(128, Duration.ofSeconds(30));

    public void clearCaches() {
        trackCache.clear();
        playCache.clear();
    }

    @GetMapping("/tracks")
    public ResponseEntity<RecordsPage<TrackRecord>> tracks(
            @RequestParam Map<String, String> parameters) {
        var query = RecordsQuery.parse(parameters, false);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(trackCache.get(query, () -> recordsService.tracks(query)));
    }

    @GetMapping("/plays")
    public ResponseEntity<RecordsPage<PlaybackRecord>> plays(
            @RequestParam Map<String, String> parameters) {
        var query = RecordsQuery.parse(parameters, true);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(playCache.get(query, () -> recordsService.plays(query)));
    }
}
