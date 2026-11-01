package com.radioparadise.tracker.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.radioparadise.tracker.service.ExpiringCache;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class MusicBrainzClient {
    public record Result(String status, String isrc, String source,
                         String spotifyUrl, String youtubeMusicUrl) {
        public static Result unresolved(String status) {
            return new Result(status, null, null, null, null);
        }
    }

    private record Lookup(String artist, String title, String album, Double durationSeconds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResponse(Integer count, List<Recording> recordings) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recording(String id, String title, Long length, Boolean video, String disambiguation,
                            @JsonProperty("artist-credit") List<Credit> credits,
                            List<Release> releases, List<String> isrcs, List<Relation> relations) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Credit(String name, String joinphrase) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Release(String title) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Relation(String type, Boolean ended, Url url) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Url(String resource) {}

    private static final int SEARCH_LIMIT = 25;
    private final RestClient client;
    private final ExpiringCache<Lookup, Result> cache = new ExpiringCache<>(256, Duration.ofSeconds(30));
    private long nextRequestNanos;

    public MusicBrainzClient(@Value("${musicbrainz.api.base-url:https://musicbrainz.org}") String baseUrl,
                            ObservationRegistry observations) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", "RadioParadiseTracker/1.0 (https://github.com/alpercandemir/rparadise-tracker)")
                .observationRegistry(observations).build();
    }

    public Result find(String artist, String title, String album, Double durationSeconds) {
        if (isBlank(artist) || isBlank(title) || isBlank(album)
                || durationSeconds == null || !Double.isFinite(durationSeconds) || durationSeconds <= 0) {
            return Result.unresolved("insufficient_metadata");
        }
        var lookup = new Lookup(artist, title, album, durationSeconds);
        return cache.get(lookup, () -> fetch(lookup));
    }

    private Result fetch(Lookup lookup) {
        try {
            String query = "recording:" + quoted(lookup.title()) + " AND artist:" + quoted(lookup.artist())
                    + " AND release:" + quoted(lookup.album());
            awaitRequestSlot();
            var response = client.get().uri("/ws/2/recording?query={query}&fmt=json&limit={limit}", query, SEARCH_LIMIT)
                    .retrieve().body(SearchResponse.class);
            if (response == null || response.count() == null || response.recordings() == null) {
                return Result.unresolved("unavailable");
            }
            // Never accept a candidate from an incomplete result set.
            if (response.count() > response.recordings().size()) {
                return Result.unresolved("ambiguous");
            }
            var matches = response.recordings().stream().filter(recording -> matches(lookup, recording)).toList();
            if (matches.size() != 1) {
                return Result.unresolved(matches.isEmpty() ? "not_found" : "ambiguous");
            }
            return fetchRecording(lookup, matches.getFirst().id());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unresolved("unavailable");
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("MusicBrainz recording lookup unavailable ({})", e.getClass().getSimpleName());
            return Result.unresolved("unavailable");
        }
    }

    private Result fetchRecording(Lookup lookup, String id) throws InterruptedException {
        UUID.fromString(id);
        awaitRequestSlot();
        var recording = client.get().uri("/ws/2/recording/{id}?inc=isrcs+url-rels+artist-credits&fmt=json", id)
                .retrieve().body(Recording.class);
        if (recording == null || !id.equals(recording.id()) || !matchesMetadata(lookup, recording)) {
            return Result.unresolved("unavailable");
        }
        var isrcs = recording.isrcs() == null ? List.<String>of() : recording.isrcs().stream()
                .filter(value -> value != null && value.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}"))
                .distinct().toList();
        if (isrcs.size() > 1) {
            return Result.unresolved("ambiguous");
        }
        return new Result("matched", isrcs.isEmpty() ? null : isrcs.getFirst(),
                "https://musicbrainz.org/recording/" + id,
                streamingUrl(recording, "open.spotify.com", "/track/[A-Za-z0-9]{22}"),
                streamingUrl(recording, "music.youtube.com", "/watch"));
    }

    private static boolean matches(Lookup lookup, Recording recording) {
        return recording != null && recording.id() != null && matchesMetadata(lookup, recording)
                && recording.releases() != null && recording.releases().stream()
                .anyMatch(release -> release != null && same(lookup.album(), release.title()));
    }

    private static boolean matchesMetadata(Lookup lookup, Recording recording) {
        // Disambiguation can distinguish versions even when the displayed title is identical.
        return !Boolean.TRUE.equals(recording.video()) && isBlank(recording.disambiguation())
                && same(lookup.title(), recording.title()) && same(lookup.artist(), artistCredit(recording))
                && recording.length() != null && recording.length() > 0
                && Math.abs(lookup.durationSeconds() - recording.length() / 1000.0) <= 3;
    }

    private static String artistCredit(Recording recording) {
        if (recording.credits() == null) {
            return "";
        }
        var credit = new StringBuilder();
        for (Credit part : recording.credits()) {
            if (part == null || isBlank(part.name())) {
                return "";
            }
            credit.append(part.name()).append(part.joinphrase() == null ? "" : part.joinphrase());
        }
        return credit.toString();
    }

    private static String streamingUrl(Recording recording, String host, String pathPattern) {
        if (recording.relations() == null) {
            return null;
        }
        var urls = recording.relations().stream()
                .filter(relation -> relation != null && !Boolean.TRUE.equals(relation.ended())
                        && ("free streaming".equals(relation.type()) || "streaming".equals(relation.type())))
                .map(Relation::url).filter(url -> url != null && url.resource() != null)
                .map(Url::resource).filter(url -> validStreamingUrl(url, host, pathPattern)).distinct().toList();
        return urls.size() == 1 ? urls.getFirst() : null;
    }

    private static boolean validStreamingUrl(String value, String host, String pathPattern) {
        try {
            URI uri = URI.create(value);
            boolean validOrigin = "https".equals(uri.getScheme()) && host.equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getFragment() == null;
            boolean validPath = uri.getPath() != null && uri.getPath().matches(pathPattern);
            boolean validQuery = host.equals("music.youtube.com")
                    ? uri.getRawQuery() != null && uri.getRawQuery().matches("v=[A-Za-z0-9_-]{11}")
                    : uri.getRawQuery() == null;
            return validOrigin && validPath && validQuery;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private synchronized void awaitRequestSlot() throws InterruptedException {
        long remaining = nextRequestNanos - System.nanoTime();
        if (remaining > 0) {
            Thread.sleep(Duration.ofNanos(remaining));
        }
        // MusicBrainz permits at most one request per second per source IP.
        nextRequestNanos = System.nanoTime() + Duration.ofMillis(1100).toNanos();
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private static boolean same(String first, String second) {
        return !isBlank(first) && !isBlank(second) && normalize(first).equals(normalize(second));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", " ").strip();
    }
}
