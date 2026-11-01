package com.radioparadise.tracker.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.radioparadise.tracker.service.ExpiringCache;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Slf4j
public class LrclibClient {
    public record Result(String status, String lyrics, String source) {
        public static Result unresolved(String status) {
            return new Result(status, null, null);
        }
    }

    private record Lookup(String artist, String title, String album, Double duration) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Recording(Long id, String trackName, String artistName, String albumName,
                            Double duration, Boolean instrumental, String plainLyrics) {}

    private final RestClient client;
    private final ExpiringCache<Lookup, Result> cache = new ExpiringCache<>(256, Duration.ofSeconds(30));
    // Cache loads are serialized, including requests for different recordings.
    private long nextRequestNanos;
    private Instant retryAfter = Instant.MIN;

    public LrclibClient(@Value("${lrclib.api.base-url:https://lrclib.net}") String baseUrl,
                        ObservationRegistry observations) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("User-Agent", "RadioParadiseTracker/1.0 (https://github.com/alpercandemir/rparadise-tracker)")
                .observationRegistry(observations).build();
    }

    public Result find(String artist, String title, String album, Double duration) {
        if (isBlank(artist) || isBlank(title) || isBlank(album)) {
            return Result.unresolved("not_found");
        }
        Double knownDuration = duration != null && Double.isFinite(duration) && duration > 0 ? duration : null;
        var lookup = new Lookup(artist, title, album, knownDuration);
        return cache.get(lookup, () -> fetch(lookup));
    }

    private Result fetch(Lookup lookup) {
        if (Instant.now().isBefore(retryAfter)) {
            return Result.unresolved("unavailable");
        }
        try {
            long remaining = nextRequestNanos - System.nanoTime();
            if (remaining > 0) {
                Thread.sleep(Duration.ofNanos(remaining));
            }
            // Search also supports legacy songs whose duration is unknown. Validate every candidate locally.
            var recordings = client.get().uri("/api/search?track_name={title}&artist_name={artist}&album_name={album}",
                    lookup.title(), lookup.artist(), lookup.album()).retrieve().body(Recording[].class);
            return select(lookup, recordings);
        } catch (HttpClientErrorException.TooManyRequests e) {
            String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
            retryAfter = retryAt(header);
            return Result.unresolved("unavailable");
        } catch (HttpClientErrorException.NotFound e) {
            return Result.unresolved("not_found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.unresolved("unavailable");
        } catch (RestClientException e) {
            log.warn("LRCLIB lyrics unavailable ({})", e.getClass().getSimpleName());
            return Result.unresolved("unavailable");
        } finally {
            nextRequestNanos = System.nanoTime() + Duration.ofMillis(250).toNanos();
        }
    }

    private static Result select(Lookup lookup, Recording[] recordings) {
        if (recordings == null) {
            return Result.unresolved("unavailable");
        }
        // Search returns at most 20 records without pagination; a full page may hide another match.
        if (recordings.length >= 20) {
            return Result.unresolved("not_found");
        }
        Recording match = null;
        for (Recording recording : recordings) {
            if (!matches(lookup, recording)) {
                continue;
            }
            if (match != null) {
                return Result.unresolved("not_found");
            }
            match = recording;
        }
        if (match == null || (!Boolean.TRUE.equals(match.instrumental()) && isBlank(match.plainLyrics()))) {
            return Result.unresolved("not_found");
        }
        boolean instrumental = Boolean.TRUE.equals(match.instrumental());
        return new Result(instrumental ? "instrumental" : "found",
                instrumental ? null : match.plainLyrics().strip(), "https://lrclib.net/api/get/" + match.id());
    }

    private static boolean matches(Lookup lookup, Recording recording) {
        if (recording == null || recording.id() == null || recording.id() < 1) {
            return false;
        }
        boolean sameRecording = same(lookup.artist(), recording.artistName())
                && same(lookup.title(), recording.trackName()) && same(lookup.album(), recording.albumName());
        boolean matchingDuration = lookup.duration() == null || (recording.duration() != null
                && Double.isFinite(recording.duration()) && recording.duration() > 0
                && Math.abs(lookup.duration() - recording.duration()) <= 2);
        return sameRecording && matchingDuration;
    }

    private static Instant retryAt(String header) {
        if (header == null) {
            return Instant.now().plus(Duration.ofMinutes(5));
        }
        try {
            return Instant.now().plusSeconds(Math.max(0, Long.parseLong(header.strip())));
        } catch (NumberFormatException e) {
            try {
                return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException invalidDate) {
                return Instant.now().plus(Duration.ofMinutes(5));
            }
        } catch (java.time.DateTimeException | ArithmeticException e) {
            return Instant.MAX;
        }
    }

    public static boolean isSource(String source) {
        return source != null && source.matches("https://lrclib\\.net/api/get/[1-9][0-9]*");
    }

    private static boolean same(String first, String second) {
        return !isBlank(first) && !isBlank(second) && normalize(first).equals(normalize(second));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
