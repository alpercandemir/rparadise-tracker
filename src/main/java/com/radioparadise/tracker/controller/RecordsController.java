package com.radioparadise.tracker.controller;

import com.radioparadise.tracker.dto.PlaybackRecord;
import com.radioparadise.tracker.dto.RecordsPage;
import com.radioparadise.tracker.dto.TrackRecord;
import com.radioparadise.tracker.service.RecordsQuery;
import com.radioparadise.tracker.service.RecordsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordsController {
    private final RecordsService records;

    @GetMapping("/tracks")
    public RecordsPage<TrackRecord> tracks(@RequestParam Map<String, String> parameters) {
        return records.tracks(RecordsQuery.parse(parameters, false));
    }

    @GetMapping("/plays")
    public RecordsPage<PlaybackRecord> plays(@RequestParam Map<String, String> parameters) {
        return records.plays(RecordsQuery.parse(parameters, true));
    }
}
