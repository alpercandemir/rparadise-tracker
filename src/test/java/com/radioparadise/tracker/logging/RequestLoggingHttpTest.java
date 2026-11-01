package com.radioparadise.tracker.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = RequestLoggingHttpTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestLoggingHttpTest {
    @Value("${local.server.port}")
    private int port;

    @Test
    void nativeProxyHandlingSuppliesClientIpToRegisteredFilter() {
        var response = RestClient.create().get().uri("http://localhost:" + port + "/context")
                .header("X-Forwarded-For", "198.51.100.42")
                .header("X-Forwarded-Proto", "https")
                .header("User-Agent", "MDC integration test")
                .header("X-Request-ID", "integration-request")
                .retrieve().toEntity(String.class);

        assertThat(response.getBody()).contains("198.51.100.42", "MDC integration test",
                "integration-request", "/context");
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isEqualTo("integration-request");
        assertThat(response.getHeaders().getFirst("X-Trace-ID")).matches("[0-9a-f]{32}");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({RequestLoggingFilter.class, ContextController.class})
    static class TestApplication {}

    @RestController
    static class ContextController {
        @GetMapping("/context")
        Map<String, String> context() {
            return MDC.getCopyOfContextMap();
        }
    }
}
