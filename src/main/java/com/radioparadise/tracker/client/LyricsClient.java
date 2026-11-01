package com.radioparadise.tracker.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.radioparadise.tracker.service.ExpiringCache;
import com.radioparadise.tracker.dto.RadioParadiseResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClientException;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.HtmlUtils;
import java.net.http.HttpClient;
import java.time.Duration;
import java.text.Normalizer;
import java.util.Locale;

@Component
@Slf4j
public class LyricsClient {
    public static final String SOURCE = "https://radioparadise.com/music";
    public record LyricsResponse(@JsonProperty("song_id") Long id, String lyrics, String length) {}
    public record Result(String status, String lyrics, String source, Double duration, Long rpSongId) {}
    private record Lookup(Long rpSongId, String artist, String title, String album, String channel) {}
    private final RestClient client;
    private final RadioParadiseClient radioParadise;
    private final ExpiringCache<Lookup, Result> cache = new ExpiringCache<>(256, Duration.ofSeconds(30));

    public LyricsClient(@Value("${radioparadise.api.base-url}") String baseUrl,
                        ObservationRegistry observations, RadioParadiseClient radioParadise) {
        this.radioParadise = radioParadise;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .observationRegistry(observations).build();
    }

    public Result find(Long rpSongId, String artist, String title, String album, String channel) {
        var lookup = new Lookup(rpSongId, artist, title, album, channel);
        return cache.get(lookup, () -> fetch(lookup));
    }

    private Result fetch(Lookup lookup) {
        Long id = lookup.rpSongId();
        try {
            if (id == null || id < 1) {
                id = resolveSongId(lookup);
                if (id == null) {
                    return result("not_found", null, null, null);
                }
            }
            var response = client.get().uri("/siteapi.php?file=music::song&song_id={id}", id)
                    .retrieve().body(LyricsResponse.class);
            if (response == null || !id.equals(response.id())) {
                return result("unavailable", null, null, id);
            }
            String text = plainText(response.lyrics());
            String status = lyricsStatus(text);
            return result(status, "not_found".equals(status) ? null : text, duration(response.length()), id);
        } catch (HttpClientErrorException.NotFound e) {
            return result("not_found", null, null, id);
        } catch (RestClientException | IllegalStateException e) {
            log.warn("RP lyrics unavailable for song ID {} ({})", id, e.getClass().getSimpleName());
            return result("unavailable", null, null, id);
        }
    }

    private Long resolveSongId(Lookup lookup) {
        var history = radioParadise.getSongHistory(lookup.channel())
                .orElseThrow(() -> new IllegalStateException("RP history unavailable"));
        for (var song : history) {
            if (matchesRecording(lookup, song)) {
                return song.getRpSongId();
            }
        }
        return null;
    }

    private static boolean matchesRecording(Lookup lookup, RadioParadiseResponse song) {
        return song.getRpSongId() != null && song.getRpSongId() > 0
                && matches(lookup.artist(), song.getArtist())
                && matches(lookup.title(), song.getTitle())
                && matches(lookup.album(), song.getAlbum());
    }

    private static String lyricsStatus(String text) {
        if (text.isBlank() || text.equalsIgnoreCase("(lyrics unavailable)")) {
            return "not_found";
        }
        return text.matches("(?i)[\\[(]?instrumental[\\])]?[.!]?") ? "instrumental" : "found";
    }

    private static Result result(String status, String lyrics, Double duration, Long id) {
        return new Result(status, lyrics, id == null ? SOURCE : SOURCE + "/song/" + id, duration, id);
    }

    public static boolean isRadioParadiseSource(String source) {
        return source != null && (source.equals(SOURCE) || source.startsWith(SOURCE + "/song/"));
    }

    private static boolean matches(String expected, String actual) {
        return expected != null && actual != null && normalize(expected).equals(normalize(actual));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String plainText(String html) {
        if (html == null) {
            return "";
        }
        String text = html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>", "")
                .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li)\\s*>", "\n")
                .replaceAll("<[^>]*>", "");
        return HtmlUtils.htmlUnescape(text).replace('\u00a0', ' ').strip();
    }

    private static Double duration(String length) {
        if (length == null || length.isBlank()) {
            return null;
        }
        try {
            double seconds = 0;
            for (String part : length.strip().split(":")) {
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            return Double.isFinite(seconds) && seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
