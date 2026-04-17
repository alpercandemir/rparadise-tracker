package com.radioparadise.tracker.controller;

import com.radioparadise.tracker.model.Song;
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
    public ResponseEntity<List<Song>> getAllSongs() {
        return ResponseEntity.ok(songService.getAllSongs());
    }

    @GetMapping("/recent")
    public ResponseEntity<List<Song>> getRecentSongs() {
        return ResponseEntity.ok(songService.getRecentSongs());
    }

    @GetMapping("/most-played")
    public ResponseEntity<List<Song>> getMostPlayedSongs() {
        return ResponseEntity.ok(songService.getMostPlayedSongs());
    }

    @GetMapping("/channel/{channelId}")
    public ResponseEntity<List<Song>> getSongsByChannel(@PathVariable String channelId) {
        String validatedChannelId = RadioParadiseChannel.fromId(channelId).id();
        return ResponseEntity.ok(songService.getSongsByChannel(validatedChannelId));
    }
}
