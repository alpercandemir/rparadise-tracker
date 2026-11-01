package com.radioparadise.tracker.controller;

import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.model.RadioParadiseChannel;
import com.radioparadise.tracker.service.SongService;
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

    @GetMapping
    public ResponseEntity<List<TrackRecord>> getAllSongs() {
        return ResponseEntity.ok(songService.getAllSongs().stream().map(TrackRecord::from).toList());
    }

    @GetMapping("/recent")
    public ResponseEntity<List<TrackRecord>> getRecentSongs() {
        return ResponseEntity.ok(songService.getRecentSongs().stream().map(TrackRecord::from).toList());
    }

    @GetMapping("/most-played")
    public ResponseEntity<List<TrackRecord>> getMostPlayedSongs() {
        return ResponseEntity.ok(songService.getMostPlayedSongs().stream().map(TrackRecord::from).toList());
    }

    @GetMapping("/channel/{channelId}")
    public ResponseEntity<List<TrackRecord>> getSongsByChannel(@PathVariable String channelId) {
        String validatedChannelId = RadioParadiseChannel.fromId(channelId).id();
        return ResponseEntity.ok(songService.getSongsByChannel(validatedChannelId).stream().map(TrackRecord::from).toList());
    }
}
