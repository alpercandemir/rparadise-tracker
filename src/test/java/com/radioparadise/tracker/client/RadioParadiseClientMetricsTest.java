package com.radioparadise.tracker.client;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RadioParadiseClientMetricsTest {
    private HttpServer server;
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ObservationRegistry observations = ObservationRegistry.create();
    private int status = 200;
    private String returnedChannel;

    @BeforeEach
    void startServer() throws Exception {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/nowplaying_list_v2022", exchange -> {
            String channel = java.util.Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                    .filter(part -> part.startsWith("chan=")).findFirst().orElseThrow().substring(5);
            if (returnedChannel != null) channel = returnedChannel;
            byte[] body = ("{\"chan\":\"" + channel + "\",\"song\":[{\"song_id\":\"42\",\"duration\":\"123000\",\"artist\":\"Artist\",\"title\":\"Title\",\"cover\":\"covers/l/42.jpg\"}],\"cover_base_url\":\"https://img.radioparadise.com/\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        meters.close();
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withBean(ObservationRegistry.class, () -> observations)
                .withBean(RadioParadiseClient.class)
                .withPropertyValues("radioparadise.api.base-url=http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void repeatedReadsOfSameChannelShareOneUpstreamCall() {
        context().run(ctx -> {
            var client = ctx.getBean(RadioParadiseClient.class);
            var current = client.getCurrentSong("0").orElseThrow();
            assertThat(current.getRpSongId()).isEqualTo(42);
            assertThat(current.getDurationMillis()).isEqualTo(123000);
            assertThat(current.getCover()).isEqualTo("https://img.radioparadise.com/covers/l/42.jpg");
            client.getCurrentSong("0");
            assertThat(client.getSongHistory("0").orElseThrow()).hasSize(1);
            assertThat(meters.find("http.client.requests").timer().count()).isEqualTo(1);
            client.getCurrentSong("1");
            assertThat(meters.find("http.client.requests").timer().count()).isEqualTo(2);
        });
    }

    @Test
    void rejectsHistoryFromAnotherChannel() {
        returnedChannel = "2";
        context().run(ctx -> assertThat(ctx.getBean(RadioParadiseClient.class).getCurrentSong("0")).isEmpty());
    }

    @Test
    void recordsSuccessfulCallsWithoutExpandingChannelValuesIntoMetricUris() {
        context().run(ctx -> {
            var client = ctx.getBean(RadioParadiseClient.class);
            assertThat(client.getCurrentSong("0")).isPresent();
            assertThat(client.getCurrentSong("2")).isPresent();
            var timers = meters.find("http.client.requests").timers();
            assertThat(timers).hasSize(1);
            var timer = timers.iterator().next();
            assertThat(timer.count()).isEqualTo(2);
            assertThat(timer.getId().getTag("uri")).isEqualTo("/api/nowplaying_list_v2022?mode=wip-channel&chan={channel}&list_num=100");
            assertThat(timer.getId().getTag("status")).isEqualTo("200");
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isPositive();
        });
    }

    @Test
    void recordsUpstreamHttpErrorsWhilePreservingEmptyResult() {
        status = 503;
        context().run(ctx -> {
            assertThat(ctx.getBean(RadioParadiseClient.class).getCurrentSong("0")).isEmpty();
            var timer = meters.find("http.client.requests").tag("status", "503").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.getId().getTag("outcome")).isEqualTo("SERVER_ERROR");
        });
    }

    @Test
    void recordsTransportErrorsWhilePreservingEmptyResult() {
        context().run(ctx -> {
            server.stop(0);
            assertThat(ctx.getBean(RadioParadiseClient.class).getCurrentSong("0")).isEmpty();
            var timers = meters.find("http.client.requests").timers();
            assertThat(timers).hasSize(1);
            var timer = timers.iterator().next();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.getId().getTag("status")).isEqualTo("CLIENT_ERROR");
        });
    }
}
