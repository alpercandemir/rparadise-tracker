package com.radioparadise.tracker.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MusicBrainzClientTest {
    private static final String ID = "5a6cd4c6-3d1f-4d71-8803-1b327674fb86";
    private static final String SPOTIFY = "https://open.spotify.com/track/0123456789ABCDEFGHIJKL";
    private static final String YOUTUBE = "https://music.youtube.com/watch?v=Abc123_ef-g";
    private static final String RECORDING = """
            {"id":"%s","title":"Title","length":120000,"video":false,"disambiguation":"",
             "artist-credit":[{"name":"Artist"}],"releases":[{"title":"Album"}],
             "isrcs":["GBABC1200001"],"unknown":true,"relations":[]}
            """.formatted(ID);
    private HttpServer server;
    private MusicBrainzClient client;
    private String searchBody;
    private String detailBody;
    private String searchQuery;
    private int searchCalls;
    private int detailCalls;
    private int status;
    private long searchStarted;
    private long detailStarted;

    @BeforeEach
    void start() throws Exception {
        searchBody = search(RECORDING, 1);
        detailBody = RECORDING;
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ws/2/recording", exchange -> {
            boolean detail = exchange.getRequestURI().getPath().endsWith(ID);
            if (detail) {
                detailCalls++;
                detailStarted = System.nanoTime();
            } else {
                searchCalls++;
                searchStarted = System.nanoTime();
                searchQuery = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            }
            byte[] bytes = (detail ? detailBody : searchBody).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new MusicBrainzClient("http://127.0.0.1:" + server.getAddress().getPort(), ObservationRegistry.NOOP);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void savesUniqueRecordingIsrcAndExplicitStreamingLinksWithSource() {
        detailBody = RECORDING.replace("\"relations\":[]", "\"relations\":[" + relation(SPOTIFY) + "," + relation(YOUTUBE) + "]");

        var result = client.find("ARTIST", "Title", "Album", 122.0);

        assertThat(result.status()).isEqualTo("matched");
        assertThat(result.isrc()).isEqualTo("GBABC1200001");
        assertThat(result.source()).isEqualTo("https://musicbrainz.org/recording/" + ID);
        assertThat(result.spotifyUrl()).isEqualTo(SPOTIFY);
        assertThat(result.youtubeMusicUrl()).isEqualTo(YOUTUBE);
        assertThat(searchQuery).contains("recording:\"Title\" AND artist:\"ARTIST\" AND release:\"Album\"");
        assertThat(detailStarted - searchStarted).isGreaterThanOrEqualTo(1_000_000_000L);
        assertThat(client.find("ARTIST", "Title", "Album", 122.0)).isEqualTo(result);
        assertThat(searchCalls).isEqualTo(1);
        assertThat(detailCalls).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"title", "artist", "album", "duration", "video", "version", "disambiguation"})
    void rejectsDifferentRecordingsDespiteSearchRanking(String mismatch) {
        String candidate = switch (mismatch) {
            case "title" -> RECORDING.replace("Title", "Other title");
            case "artist" -> RECORDING.replace("Artist", "Other artist");
            case "album" -> RECORDING.replace("Album", "Other album");
            case "duration" -> RECORDING.replace("120000", "130000");
            case "video" -> RECORDING.replace("\"video\":false", "\"video\":true");
            case "version" -> RECORDING.replace("Title", "Title (live)");
            default -> RECORDING.replace("\"disambiguation\":\"\"", "\"disambiguation\":\"live\"");
        };
        searchBody = search(candidate, 1);

        assertThat(client.find("Artist", "Title", "Album", 120.0).status()).isEqualTo("not_found");
        assertThat(detailCalls).isZero();
    }

    @Test
    void doesNotChooseBetweenDifferentRecordingsOrTruncatedResults() {
        searchBody = search(RECORDING + "," + RECORDING.replace(ID, "dd54d334-2b7b-443f-af60-980d38b89f5c"), 2);
        assertThat(client.find("Artist", "Title", "Album", 120.0).status()).isEqualTo("ambiguous");
        searchBody = search(RECORDING, 26);
        assertThat(client.find("Artist", "Title", "Album", 121.0).status()).isEqualTo("ambiguous");
        assertThat(detailCalls).isZero();
    }

    @Test
    void refusesConflictingIsrcsAndMismatchedDetailResponses() {
        detailBody = RECORDING.replace("\"GBABC1200001\"", "\"GBABC1200001\",\"GBABC1200002\"");
        assertThat(client.find("Artist", "Title", "Album", 120.0).status()).isEqualTo("ambiguous");
        detailBody = RECORDING.replace(ID, "dd54d334-2b7b-443f-af60-980d38b89f5c");
        assertThat(client.find("Artist", "Title", "Album", 121.0).status()).isEqualTo("unavailable");
    }

    @Test
    void doesNotInventIsrcsOrConvertRegularYoutubeLinksIntoMusicLinks() {
        detailBody = RECORDING.replace("GBABC1200001", "invalid")
                .replace("\"relations\":[]", "\"relations\":[" + relation("https://www.youtube.com/watch?v=Abc123_ef-g") + "]");

        var result = client.find("Artist", "Title", "Album", 120.0);

        assertThat(result.status()).isEqualTo("matched");
        assertThat(result.isrc()).isNull();
        assertThat(result.youtubeMusicUrl()).isNull();
    }

    @Test
    void ignoresUnsafeAlbumEndedAndConflictingLinks() {
        String invalid = relation("https://open.spotify.com.evil.test/track/0123456789ABCDEFGHIJKL") + ","
                + relation("https://open.spotify.com/album/0123456789ABCDEFGHIJKL") + ","
                + relation(YOUTUBE).replace("\"type\":", "\"ended\":true,\"type\":") + ","
                + relation(SPOTIFY) + "," + relation(SPOTIFY.replace("0123456789", "9876543210"));
        detailBody = RECORDING.replace("\"relations\":[]", "\"relations\":[" + invalid + "]");

        var result = client.find("Artist", "Title", "Album", 120.0);

        assertThat(result.spotifyUrl()).isNull();
        assertThat(result.youtubeMusicUrl()).isNull();
    }

    @Test
    void skipsIncompleteMetadataAndCachesProviderFailures() {
        assertThat(client.find("Artist", "Title", "Album", null).status()).isEqualTo("insufficient_metadata");
        assertThat(client.find("Artist", "Title", "Album", Double.NaN).status()).isEqualTo("insufficient_metadata");
        assertThat(searchCalls).isZero();
        status = 503;
        assertThat(client.find("Artist", "Title", "Album", 120.0).status()).isEqualTo("unavailable");
        assertThat(client.find("Artist", "Title", "Album", 120.0).status()).isEqualTo("unavailable");
        assertThat(searchCalls).isEqualTo(1);
    }

    private static String search(String recordings, int count) {
        return "{\"count\":" + count + ",\"recordings\":[" + recordings + "]}";
    }

    private static String relation(String url) {
        return "{\"type\":\"free streaming\",\"url\":{\"resource\":\"" + url + "\"}}";
    }
}
