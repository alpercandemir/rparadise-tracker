package com.radioparadise.tracker.client;

import com.radioparadise.tracker.dto.RadioParadiseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Component
@Slf4j
public class RadioParadiseClient {

    private final RestClient restClient;

    public RadioParadiseClient(@Value("${radioparadise.api.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public Optional<RadioParadiseResponse> getCurrentSong(String channel) {
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

            log.info("Current song on channel {}: {} - {} ({})",
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
