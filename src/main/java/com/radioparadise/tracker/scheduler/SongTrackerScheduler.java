package com.radioparadise.tracker.scheduler;

import com.radioparadise.tracker.client.RadioParadiseClient;
import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.model.RadioParadiseChannel;
import com.radioparadise.tracker.service.InvalidSongMetadataException;
import com.radioparadise.tracker.service.SongService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SongTrackerScheduler {

    private final RadioParadiseClient radioParadiseClient;
    private final SongService songService;
    private final MeterRegistry meterRegistry;
    private final List<RadioParadiseChannel> channels;
    private final Map<String, SongKey> lastSongKeyByChannel = new HashMap<>();
    private final Map<String, AtomicLong> lastSuccessfulPollByChannel = new HashMap<>();

    public SongTrackerScheduler(
            RadioParadiseClient radioParadiseClient,
            SongService songService,
            MeterRegistry meterRegistry,
            @Value("${radioparadise.api.channels}") String configuredChannels) {
        this.radioParadiseClient = radioParadiseClient;
        this.songService = songService;
        this.meterRegistry = meterRegistry;
        this.channels =
                Arrays.stream(configuredChannels.split(","))
                        .map(String::trim)
                        .filter(channelId -> !channelId.isEmpty())
                        .map(RadioParadiseChannel::fromId)
                        .distinct()
                        .toList();
        for (RadioParadiseChannel channel : channels) {
            registerChannelMetrics(channel.id());
        }
    }

    @Scheduled(fixedDelayString = "${radioparadise.scheduler.fixed-delay}")
    public void trackCurrentSong() {
        log.debug("Checking current songs on all channels...");

        for (RadioParadiseChannel channel : channels) {
            pollChannel(channel.id());
        }
    }

    private void registerChannelMetrics(String channelId) {
        AtomicLong lastSuccess = new AtomicLong(0);
        lastSuccessfulPollByChannel.put(channelId, lastSuccess);
        Gauge.builder(
                        "radioparadise.collector.last.success.timestamp.seconds",
                        lastSuccess,
                        timestamp -> timestamp.get() / 1000.0)
                .description(
                        "Unix time of last completed successful poll; zero until first success")
                .tag("channel", channelId)
                .register(meterRegistry);
        for (String outcome : List.of("success", "failure")) {
            Counter.builder("radioparadise.collector.polls")
                    .description("Completed channel polls, including unchanged songs")
                    .tags("channel", channelId, "outcome", outcome)
                    .register(meterRegistry);
            meterRegistry.timer(
                    "radioparadise.collector.poll.duration",
                    "channel",
                    channelId,
                    "outcome",
                    outcome);
        }
    }

    private void pollChannel(String channelId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failure";
        try {
            Optional<RadioParadiseResponse> currentSong =
                    radioParadiseClient.getCurrentSong(channelId);
            if (currentSong.isEmpty()) {
                return;
            }
            saveSongIfChanged(channelId, currentSong.get());
            // Unchanged songs are healthy polls too. Persistence failures never advance freshness.
            lastSuccessfulPollByChannel
                    .get(channelId)
                    .set(meterRegistry.config().clock().wallTime());
            outcome = "success";
        } catch (InvalidSongMetadataException e) {
            log.warn("Skipping invalid song metadata on channel {}: {}", channelId, e.getMessage());
        } catch (Exception e) {
            log.error("Error tracking song on channel {}", channelId, e);
        } finally {
            meterRegistry
                    .counter(
                            "radioparadise.collector.polls",
                            "channel",
                            channelId,
                            "outcome",
                            outcome)
                    .increment();
            sample.stop(
                    meterRegistry.timer(
                            "radioparadise.collector.poll.duration",
                            "channel",
                            channelId,
                            "outcome",
                            outcome));
        }
    }

    private void saveSongIfChanged(String channelId, RadioParadiseResponse song) {
        SongKey currentSongKey = new SongKey(song.getArtist(), song.getTitle(), song.getAlbum());
        SongKey lastSongKey = lastSongKeyByChannel.get(channelId);

        if (currentSongKey.equals(lastSongKey)) {
            log.debug("Same song is still playing on channel {}, skipping save", channelId);
            return;
        }

        songService.saveOrUpdateSong(song, channelId);
        // The transactional service proxy has committed before reporting success.
        log.info(
                "Tracked song on channel {}: {} - {} ({})",
                channelId,
                song.getArtist(),
                song.getTitle(),
                song.getAlbum());
        lastSongKeyByChannel.put(channelId, currentSongKey);
    }

    private record SongKey(String artist, String title, String album) {}
}
