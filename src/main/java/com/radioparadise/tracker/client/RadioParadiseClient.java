package com.radioparadise.tracker.client;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import com.radioparadise.tracker.service.ExpiringCache;
import com.radioparadise.tracker.model.RadioParadiseChannel;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.net.URI;
import com.fasterxml.jackson.annotation.JsonProperty;

@Component
@Slf4j
public class RadioParadiseClient {

    private final RestClient restClient;
    private final ExpiringCache<String, Optional<List<RadioParadiseResponse>>> currentSongs =
            new ExpiringCache<>(3, Duration.ofSeconds(30));

    public RadioParadiseClient(@Value("${radioparadise.api.base-url}") String baseUrl,
                               ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder()
                .observationRegistry(observationRegistry)
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public Optional<RadioParadiseResponse> getCurrentSong(String channel) {
        return getSongHistory(channel).filter(songs -> !songs.isEmpty()).map(List::getFirst);
    }

    public Optional<List<RadioParadiseResponse>> getSongHistory(String channel) {
        RadioParadiseChannel.fromId(channel);
        return currentSongs.get(channel, () -> fetchSongHistory(channel));
    }

    public record NowPlayingList(List<RadioParadiseResponse> song, String chan,
                                 @JsonProperty("cover_base_url") String coverBaseUrl) {}

    private Optional<List<RadioParadiseResponse>> fetchSongHistory(String channel) {
        try {
            NowPlayingList response = restClient.get()
                    .uri("/api/nowplaying_list_v2022?mode=wip-channel&chan={channel}&list_num=100", channel)
                    .retrieve().body(NowPlayingList.class);
            if (response == null || response.song() == null || response.song().isEmpty()
                    || !channel.equals(response.chan())) {
                log.warn("Missing or mismatched RP history for channel {}", channel);
                return Optional.empty();
            }
            var songs = response.song().stream().filter(java.util.Objects::nonNull).toList();
            for (var song : songs) {
                song.setCover(coverUrl(song.getCover(), response.coverBaseUrl()));
                song.setCoverMed(coverUrl(song.getCoverMed(), response.coverBaseUrl()));
                song.setCoverSmall(coverUrl(song.getCoverSmall(), response.coverBaseUrl()));
            }
            return Optional.of(List.copyOf(songs));
        } catch (Exception e) {
            log.error("Error fetching songs from Radio Paradise API for channel {}", channel, e);
            return Optional.empty();
        }
    }

    private static String coverUrl(String path, String base) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.startsWith("//")) {
            return "https:" + path;
        }
        return URI.create(base == null || base.isBlank() ? "https://img.radioparadise.com/" : base).resolve(path).toString();
    }
}
