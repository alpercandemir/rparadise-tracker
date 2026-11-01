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

@Component
@Slf4j
public class RadioParadiseClient {

    private final RestClient restClient;
    private final ExpiringCache<String, Optional<RadioParadiseResponse>> currentSongs =
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
        RadioParadiseChannel.fromId(channel);
        return currentSongs.get(channel, () -> fetchCurrentSong(channel));
    }

    private Optional<RadioParadiseResponse> fetchCurrentSong(String channel) {
        try {
            log.debug("Fetching current song from Radio Paradise API (channel: {})", channel);

            RadioParadiseResponse response = restClient.get()
                    .uri("/api/now_playing?chan={channel}", channel)
                    .retrieve()
                    .body(RadioParadiseResponse.class);

            if (response == null) {
                log.warn("Empty API response for channel {}", channel);
                return Optional.empty();
            }

            log.debug("Current song on channel {}: {} - {} ({})",
                    channel,
                    response.getArtist(),
                    response.getTitle(),
                    response.getAlbum());

            return Optional.of(response);
        } catch (Exception e) {
            log.error("Error fetching current song from Radio Paradise API for channel {}", channel, e);
            return Optional.empty();
        }
    }
}
