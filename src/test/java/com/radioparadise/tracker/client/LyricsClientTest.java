package com.radioparadise.tracker.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class LyricsClientTest {
    HttpServer server;
    LyricsClient client;
    String body;
    int status;
    String query;

    @BeforeEach void start() throws Exception {
        status = 200;
        body = """
                {"id":12,"artistName":"Artist & Friend","trackName":"Title?","albumName":"Album",
                 "duration":123,"instrumental":false,"plainLyrics":"Example text","unknownField":true}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/get", exchange -> {
            query = exchange.getRequestURI().getRawQuery();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new LyricsClient("http://127.0.0.1:" + server.getAddress().getPort(), ObservationRegistry.NOOP);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void matchesRecordingAndEncodesMetadata() {
        var result = client.find("Artist & Friend", "Title?", "Album");
        assertThat(result.status()).isEqualTo("found");
        assertThat(result.lyrics()).isEqualTo("Example text");
        assertThat(result.duration()).isEqualTo(123);
        assertThat(result.source()).isEqualTo("https://lrclib.net/api/get/12");
        assertThat(query).contains("Artist%20%26%20Friend", "Title%3F");
    }
    @Test void rejectsDifferentRecordingRatherThanShowingWrongLyrics() {
        assertThat(client.find("Other Artist", "Title?", "Album").status()).isEqualTo("not_found");
        assertThat(client.find("Artist & Friend", "Title?", "Other Album").lyrics()).isNull();
    }
    @Test void handlesInstrumental() {
        body = body.replace("\"instrumental\":false", "\"instrumental\":true").replace("\"Example text\"", "null");
        assertThat(client.find("Artist & Friend", "Title?", "Album").status()).isEqualTo("instrumental");
    }
    @Test void distinguishesMissingLyricsFromProviderFailure() {
        status = 404;
        assertThat(client.find("Artist", "Title", "Album").status()).isEqualTo("not_found");
        status = 503;
        assertThat(client.find("Artist", "Title", "Album").status()).isEqualTo("unavailable");
    }
}
