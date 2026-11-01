package com.radioparadise.tracker.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class LyricsClientTest {
    HttpServer server;
    LyricsClient client;
    String body;
    int status;
    int detailCalls;
    int historyCalls;
    String query;

    @BeforeEach void start() throws Exception {
        status = 200;
        body = """
                {"song_id":12,"length":"3:05","lyrics":"First &amp; second<br />Next line<br>Last line","unknownField":true}
                """;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/siteapi.php", exchange -> {
            detailCalls++;
            query = exchange.getRequestURI().getRawQuery();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/nowplaying_list_v2022", exchange -> {
            historyCalls++;
            byte[] bytes = """
                    {"chan":"1","song":[{"song_id":"13","artist":"Other Artist","title":"Title?","album":"Album"},
                    {"song_id":"12","artist":"Artist & Friend","title":"Title?","album":"Album"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new LyricsClient(base, ObservationRegistry.NOOP, new RadioParadiseClient(base, ObservationRegistry.NOOP));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void fetchesByAuthoritativeRpIdAndConvertsLyricsToPlainText() {
        var result = client.find(12L, "Artist & Friend", "Title?", "Album", "1");
        assertThat(result.status()).isEqualTo("found");
        assertThat(result.lyrics()).isEqualTo("First & second\nNext line\nLast line");
        assertThat(result.duration()).isEqualTo(185);
        assertThat(result.rpSongId()).isEqualTo(12);
        assertThat(result.source()).isEqualTo("https://radioparadise.com/music/song/12");
        assertThat(query).isEqualTo("file=music::song&song_id=12");
        assertThat(historyCalls).isZero();
    }
    @Test void resolvesLegacySongsAgainstCachedHistoryByExactRecordingMetadata() {
        var result = client.find(null, "Artist & Friend", "Title?", "Album", "1");
        assertThat(result.rpSongId()).isEqualTo(12);
        assertThat(result.status()).isEqualTo("found");
        assertThat(client.find(null, "Artist & Friend", "Title?", "Other Album", "1").status()).isEqualTo("not_found");
        assertThat(client.find(null, "Wrong Artist", "Title?", "Album", "1").lyrics()).isNull();
        assertThat(detailCalls).isEqualTo(1);
        assertThat(historyCalls).isEqualTo(1);
    }
    @Test void rejectsMismatchedRpId() {
        assertThat(client.find(99L, "Artist", "Title", "Album", "1").status()).isEqualTo("unavailable");
    }
    @Test void handlesMissingLyricsAndInstrumental() {
        body = "{\"song_id\":12,\"lyrics\":\"\"}";
        assertThat(client.find(12L, "A", "T", "Album", "1").status()).isEqualTo("not_found");
        body = "{\"song_id\":13,\"lyrics\":\"(Instrumental)\"}";
        assertThat(client.find(13L, "A", "T", "Album", "1").status()).isEqualTo("instrumental");
    }
    @Test void treatsRpUnavailablePlaceholderAsMissingLyrics() {
        body = "{\"song_id\":12,\"lyrics\":\"(lyrics unavailable)\"}";
        var result = client.find(12L, "A", "T", "Album", "1");
        assertThat(result.status()).isEqualTo("not_found");
        assertThat(result.lyrics()).isNull();
    }

    @Test void cachesFailuresAndSuccessfulLookups() {
        status = 503;
        assertThat(client.find(12L, "A", "T", "Album", "1").status()).isEqualTo("unavailable");
        client.find(12L, "A", "T", "Album", "1");
        assertThat(detailCalls).isEqualTo(1);
        status = 200;
        client.find(12L, "Other", "Title", "Album", "1");
        client.find(12L, "Other", "Title", "Album", "1");
        assertThat(detailCalls).isEqualTo(2);
    }
    @Test void distinguishesMissingSongFromProviderFailure() {
        status = 404;
        assertThat(client.find(12L, "A", "T", "Album", "1").status()).isEqualTo("not_found");
        status = 503;
        assertThat(client.find(13L, "A", "T", "Album", "1").status()).isEqualTo("unavailable");
    }
    @Test void removesMarkupWithoutRenderingUntrustedHtml() {
        assertThat(LyricsClient.plainText("<p>Line &lt;one&gt;</p><script>bad()</script><style>bad</style><p>Line&nbsp;two</p>"))
                .isEqualTo("Line <one>\nLine two");
    }
}
