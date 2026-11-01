package com.radioparadise.tracker.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LrclibClientTest {
    private static final String RECORDING = """
            {"id":123,"trackName":"Title? #1","artistName":"Artist & Friend","albumName":"Album",
             "duration":123,"instrumental":false,"plainLyrics":"First line\\nSecond line","syncedLyrics":"ignored"}
            """;
    private HttpServer server;
    private LrclibClient client;
    private String body;
    private int status;
    private int calls;
    private String query;
    private String userAgent;
    private String retryAfter;

    @BeforeEach
    void start() throws Exception {
        status = 200;
        body = "[" + RECORDING + "]";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/search", exchange -> {
            calls++;
            query = exchange.getRequestURI().getRawQuery();
            userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (retryAfter != null) {
                exchange.getResponseHeaders().set("Retry-After", retryAfter);
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new LrclibClient("http://127.0.0.1:" + server.getAddress().getPort(), ObservationRegistry.NOOP);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void findsExactRecordingAndEncodesSearchParameters() {
        var result = find(123.0);

        assertThat(result.status()).isEqualTo("found");
        assertThat(result.lyrics()).isEqualTo("First line\nSecond line");
        assertThat(result.source()).isEqualTo("https://lrclib.net/api/get/123");
        assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8))
                .isEqualTo("track_name=Title? #1&artist_name=Artist & Friend&album_name=Album");
        assertThat(query).contains("%26", "%23");
        assertThat(userAgent).contains("RadioParadiseTracker/1.0", "https://github.com/alpercandemir/rparadise-tracker");
    }

    @Test
    void normalizesCaseUnicodeAndWhitespaceWithoutRemovingVersionMarkers() {
        var result = client.find("  ARTIST & Friend  ", "Ｔｉｔｌｅ?  #1", " album ", 123.0);

        assertThat(result.status()).isEqualTo("found");
        assertThat(client.find("Artist & Friend", "Title? #1 (Live)", "Album", 123.0).status()).isEqualTo("not_found");
    }

    @ParameterizedTest
    @CsvSource({"121,true", "125,true", "125.01,false", "120.99,false"})
    void requiresKnownDurationToMatchWithinTwoSeconds(double duration, boolean accepted) {
        assertThat(find(duration).status()).isEqualTo(accepted ? "found" : "not_found");
    }

    @ParameterizedTest
    @ValueSource(strings = {"artist", "title", "album", "duration", "id"})
    void rejectsDifferentOrIncompleteRecordingMetadata(String field) {
        String recording = switch (field) {
            case "artist" -> RECORDING.replace("Artist & Friend", "Cover Artist");
            case "title" -> RECORDING.replace("Title? #1", "Title? #1 (Live)");
            case "album" -> RECORDING.replace("Album", "Other Album");
            case "duration" -> RECORDING.replace("\"duration\":123", "\"duration\":null");
            case "id" -> RECORDING.replace("\"id\":123", "\"id\":0");
            default -> throw new IllegalArgumentException(field);
        };
        body = "[" + recording + "]";

        assertThat(find(123.0).status()).isEqualTo("not_found");
    }

    @Test
    void acceptsUniqueExactAlbumMatchWhenDurationIsUnknown() {
        assertThat(find(null).status()).isEqualTo("found");
    }

    @Test
    void rejectsAmbiguousRecordingsEvenIfOnlyOneHasPlainLyrics() {
        body = "[" + RECORDING + "," + RECORDING.replace("123", "124").replace("First line\\nSecond line", "") + "]";

        assertThat(find(123.0).status()).isEqualTo("not_found");
    }

    @Test
    void rejectsPotentiallyTruncatedSearchResults() {
        body = "[" + String.join(",", java.util.Collections.nCopies(20, RECORDING)) + "]";

        assertThat(find(123.0).status()).isEqualTo("not_found");
    }

    @Test
    void acceptsInstrumentalWithoutLyricsAndRetainsAttribution() {
        body = "[" + RECORDING.replace("\"instrumental\":false", "\"instrumental\":true") + "]";

        var result = find(123.0);

        assertThat(result.status()).isEqualTo("instrumental");
        assertThat(result.lyrics()).isNull();
        assertThat(result.source()).isEqualTo("https://lrclib.net/api/get/123");
    }

    @Test
    void ignoresSyncedOnlyRecordsWhenPlainLyricsAreMissing() {
        body = "[" + RECORDING.replace("\"First line\\nSecond line\"", "null") + "]";

        assertThat(find(123.0).status()).isEqualTo("not_found");
    }

    @ParameterizedTest
    @CsvSource({"404,[],not_found", "503,[],unavailable", "429,[],unavailable",
            "200,[],not_found", "200,null,unavailable", "200,invalid,unavailable"})
    void distinguishesEmptyResultsAndProviderFailures(int responseStatus, String responseBody, String expected) {
        status = responseStatus;
        body = responseBody;

        assertThat(find(123.0).status()).isEqualTo(expected);
        find(123.0);
        assertThat(calls).isEqualTo(1);
    }

    @Test
    void coalescesConcurrentLookupsAndCachesSuccessfulResults() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<LrclibClient.Result>>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> find(123.0));
            }
            for (var result : executor.invokeAll(tasks)) {
                assertThat(result.get().status()).isEqualTo("found");
            }
        }
        assertThat(calls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"seconds", "date", "invalid", "missing"})
    void rateLimitCooldownAppliesToDifferentRecordings(String headerType) {
        status = 429;
        retryAfter = switch (headerType) {
            case "seconds" -> "120";
            case "date" -> ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2)
                    .format(DateTimeFormatter.RFC_1123_DATE_TIME);
            case "invalid" -> "invalid";
            case "missing" -> null;
            default -> throw new IllegalArgumentException(headerType);
        };
        assertThat(find(123.0).status()).isEqualTo("unavailable");
        status = 200;

        assertThat(client.find("Other Artist", "Other Title", "Album", 180.0).status()).isEqualTo("unavailable");
        assertThat(calls).isEqualTo(1);
    }

    @Test
    void skipsLookupsWithoutEnoughRecordingMetadata() {
        for (String missing : List.of("", "  ")) {
            assertThat(client.find(missing, "Title", "Album", null).status()).isEqualTo("not_found");
            assertThat(client.find("Artist", missing, "Album", null).status()).isEqualTo("not_found");
            assertThat(client.find("Artist", "Title", missing, null).status()).isEqualTo("not_found");
        }
        assertThat(client.find("Artist", "Title", null, null).status()).isEqualTo("not_found");
        assertThat(calls).isZero();
    }

    private LrclibClient.Result find(Double duration) {
        return client.find("Artist & Friend", "Title? #1", "Album", duration);
    }
}
