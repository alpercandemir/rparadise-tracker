package com.radioparadise.tracker.scheduler;

import com.radioparadise.tracker.client.RadioParadiseClient;
import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.service.SongService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SongTrackerScheduler {

    private final RadioParadiseClient radioParadiseClient;
    private final SongService songService;

    @Value("${radioparadise.api.channels}")
    private List<String> channels;

    private final Map<String, String> lastSongKeyByChannel = new HashMap<>();

    @Scheduled(fixedDelayString = "${radioparadise.scheduler.fixed-delay}")
    public void trackCurrentSong() {
        log.debug("Checking current songs on all channels...");

        for (String channel : channels) {
            try {
                RadioParadiseResponse currentSong = radioParadiseClient.getCurrentSong(channel);

                if (currentSong != null) {
                    String currentSongKey = generateSongKey(currentSong);
                    String lastSongKey = lastSongKeyByChannel.get(channel);

                    // Only save if the song has changed on this channel
                    if (!currentSongKey.equals(lastSongKey)) {
                        songService.saveOrUpdateSong(currentSong, channel);
                        lastSongKeyByChannel.put(channel, currentSongKey);
                    } else {
                        log.debug("Same song is still playing on channel {}, skipping save", channel);
                    }
                }
            } catch (Exception e) {
                log.error("Error tracking song on channel {}", channel, e);
            }
        }
    }

    private String generateSongKey(RadioParadiseResponse song) {
        return song.getArtist() + "|" + song.getTitle() + "|" + song.getAlbum();
    }
}
