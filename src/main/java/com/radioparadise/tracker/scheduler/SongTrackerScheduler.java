package com.radioparadise.tracker.scheduler;

import com.radioparadise.tracker.client.RadioParadiseClient;
import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.RadioParadiseChannel;
import com.radioparadise.tracker.service.SongService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
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
    private String configuredChannels;

    private final Map<String, String> lastSongKeyByChannel = new HashMap<>();

    @Scheduled(fixedDelayString = "${radioparadise.scheduler.fixed-delay}")
    public void trackCurrentSong() {
        log.debug("Checking current songs on all channels...");

        List<RadioParadiseChannel> channels = parseConfiguredChannels();
        for (RadioParadiseChannel channel : channels) {
            String channelId = channel.id();
            try {
                radioParadiseClient.getCurrentSong(channelId).ifPresent(currentSong -> saveSongIfChanged(channelId, currentSong));
            } catch (Exception e) {
                log.error("Error tracking song on channel {}", channelId, e);
            }
        }
    }

    private List<RadioParadiseChannel> parseConfiguredChannels() {
        return Arrays.stream(configuredChannels.split(","))
                .map(String::trim)
                .filter(channelId -> !channelId.isEmpty())
                .map(RadioParadiseChannel::fromId)
                .toList();
    }

    private void saveSongIfChanged(String channelId, RadioParadiseResponse song) {
        String currentSongKey = generateSongKey(song);
        String lastSongKey = lastSongKeyByChannel.get(channelId);

        if (currentSongKey.equals(lastSongKey)) {
            log.debug("Same song is still playing on channel {}, skipping save", channelId);
            return;
        }

        songService.saveOrUpdateSong(song, channelId);
        lastSongKeyByChannel.put(channelId, currentSongKey);
    }

    private String generateSongKey(RadioParadiseResponse song) {
        return song.getArtist() + "|" + song.getTitle() + "|" + song.getAlbum();
    }
}
