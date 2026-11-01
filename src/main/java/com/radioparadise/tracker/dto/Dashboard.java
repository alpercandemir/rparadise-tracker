package com.radioparadise.tracker.dto;

import java.util.List;
import java.util.Map;

public record Dashboard(List<TrackRecord> latestTracks, long totalTracks,
                        Map<String, Long> tracksByChannel, Map<String, Long> tracksByDecade) {
    public Dashboard {
        latestTracks = List.copyOf(latestTracks);
        tracksByChannel = Map.copyOf(tracksByChannel);
        tracksByDecade = Map.copyOf(tracksByDecade);
    }
}
