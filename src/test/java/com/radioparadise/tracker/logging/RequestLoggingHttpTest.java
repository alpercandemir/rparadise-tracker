package com.radioparadise.tracker.logging;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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
    @TestComponent
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(RequestLoggingFilter.class)
    static class TestApplication {
        @Bean
        ServletRegistrationBean<HttpServlet> contextServlet() {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response)
                        throws IOException {
                    response.setContentType("text/plain");
                    response.getWriter().write(MDC.getCopyOfContextMap().toString());
                }
            }, "/context");
        }
    }
}
