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

    @BeforeEach
    void startServer() throws Exception {
        observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/now_playing", exchange -> {
            byte[] body = "{\"artist\":\"Artist\",\"title\":\"Title\"}".getBytes(StandardCharsets.UTF_8);
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
    void recordsSuccessfulCallsWithoutExpandingChannelValuesIntoMetricUris() {
        context().run(ctx -> {
            var client = ctx.getBean(RadioParadiseClient.class);
            assertThat(client.getCurrentSong("0")).isPresent();
            assertThat(client.getCurrentSong("2")).isPresent();
            var timers = meters.find("http.client.requests").timers();
            assertThat(timers).hasSize(1);
            var timer = timers.iterator().next();
            assertThat(timer.count()).isEqualTo(2);
            assertThat(timer.getId().getTag("uri")).isEqualTo("/api/now_playing?chan={channel}");
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
