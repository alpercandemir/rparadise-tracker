package com.radioparadise.tracker.client;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class LyricsClient {
    public record LyricsResponse(Long id, String trackName, String artistName, String albumName,
                                 Double duration, boolean instrumental, String plainLyrics) {}
    public record Result(String status, String lyrics, String source, Double duration) {}
    private final RestClient client;

    public LyricsClient(@Value("${lyrics.api.base-url:https://lrclib.net}") String baseUrl,
                        ObservationRegistry observations) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .observationRegistry(observations)
                .defaultHeader("User-Agent", "RadioParadiseTracker/1.0 (https://github.com/alpercandemir/rparadise-tracker)")
                .build();
    }

    public Result find(String artist, String title, String album) {
        try {
            var response = client.get()
                    .uri("/api/get?artist_name={artist}&track_name={title}&album_name={album}", artist, title, album)
                    .retrieve().body(LyricsResponse.class);
            if (response == null) return new Result("unavailable", null, null, null);
            if (!matches(artist, response.artistName()) || !matches(title, response.trackName())
                    || !matches(album, response.albumName())) return new Result("not_found", null, null, null);
            String status = response.instrumental() ? "instrumental"
                    : response.plainLyrics() != null && !response.plainLyrics().isBlank() ? "found" : "not_found";
            return new Result(status, response.plainLyrics(),
                    response.id() == null ? null : "https://lrclib.net/api/get/" + response.id(), response.duration());
        } catch (HttpClientErrorException.NotFound e) {
            return new Result("not_found", null, null, null);
        } catch (Exception e) {
            return new Result("unavailable", null, null, null);
        }
    }

    private static boolean matches(String expected, String actual) {
        return actual != null && expected.strip().equalsIgnoreCase(actual.strip());
    }
}
