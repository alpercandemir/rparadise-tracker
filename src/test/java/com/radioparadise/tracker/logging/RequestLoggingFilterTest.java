package com.radioparadise.tracker.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {
    private final RequestLoggingFilter filter = new RequestLoggingFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/songs");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void captureLogs() {
        MDC.clear();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanUp() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
    }

    @Test
    void exposesContextDuringRequestAndLogsCompletionWithoutQueryOrCredentials() throws Exception {
        request.setRemoteAddr("192.0.2.10");
        request.setQueryString("token=secret");
        request.addHeader("User-Agent", "Mozilla/5.0 Test Browser");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Cookie", "session=secret");
        request.addHeader("X-Request-ID", "support-123");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        filter.doFilter(request, response, (incoming, outgoing) -> {
            assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(Map.of(
                    "traceId", "4bf92f3577b34da6a3ce929d0e0e4736",
                    "requestId", "support-123",
                    "clientIp", "192.0.2.10",
                    "userAgent", "Mozilla/5.0 Test Browser",
                    "httpMethod", "GET",
                    "requestPath", "/api/songs"));
            response.setStatus(201);
        });

        assertThat(response.getHeader("X-Trace-ID")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(response.getHeader("X-Request-ID")).isEqualTo("support-123");
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getMDCPropertyMap()).containsEntry("httpStatus", "201");
            assertThat(Long.parseLong(event.getMDCPropertyMap().get("durationMs"))).isNotNegative();
            assertThat(event.getMDCPropertyMap().toString()).doesNotContain("secret", "token", "session");
        });
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad value", "injected\r\nINFO forged", "a" +
            "1234567890123456789012345678901234567890123456789012345678901234"})
    void replacesMissingOrUnsafeRequestIds(String suppliedId) throws Exception {
        if (suppliedId != null) {
            request.addHeader("X-Request-ID", suppliedId);
        }

        filter.doFilter(request, response, (incoming, outgoing) -> {});

        assertThat(response.getHeader("X-Request-ID")).matches("[0-9a-f]{32}");
        assertThat(response.getHeader("X-Trace-ID")).matches("[0-9a-f]{32}");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-trace-parent",
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\nforged"})
    void replacesInvalidTraceParents(String traceParent) throws Exception {
        request.addHeader("traceparent", traceParent);

        filter.doFilter(request, response, (incoming, outgoing) -> {});

        assertThat(response.getHeader("X-Trace-ID")).matches("[0-9a-f]{32}")
                .isNotEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @Test
    void boundsAndEscapesUntrustedValuesAndDoesNotReadForwardedIpItself() throws Exception {
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.42");
        request.addHeader("User-Agent", "Agent\r\n\t\u001b\u2028\u2029\"\\" + "x".repeat(600));
        request.setRequestURI("/" + "p".repeat(2100));

        filter.doFilter(request, response, (incoming, outgoing) -> {
            assertThat(MDC.get("clientIp")).isEqualTo("192.0.2.10");
            assertThat(MDC.get("userAgent")).startsWith("Agent      \\\"\\\\")
                    .doesNotContain("\r", "\n", "\t", "\u001b", "\u2028", "\u2029")
                    .hasSize(514);
            assertThat(MDC.get("requestPath")).hasSize(2048);
        });
    }

    @Test
    void restoresExistingContextAfterAnEscapingExceptionAndLogs500() {
        MDC.put("otherContext", "preserved");
        MDC.put("traceId", "outer-trace");
        ServletException failure = new ServletException("test failure");

        assertThatThrownBy(() -> filter.doFilter(request, response, (incoming, outgoing) -> {
            assertThat(MDC.get("otherContext")).isEqualTo("preserved");
            throw failure;
        })).isSameAs(failure);

        assertThat(MDC.getCopyOfContextMap()).containsExactlyInAnyOrderEntriesOf(
                Map.of("otherContext", "preserved", "traceId", "outer-trace"));
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMDCPropertyMap()).containsEntry("httpStatus", "500");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void reusesOriginalContextDuringErrorDispatchWithoutDuplicateCompletion() throws Exception {
        filter.doFilter(request, response, (incoming, outgoing) -> response.sendError(404));
        String traceId = response.getHeader("X-Trace-ID");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/songs");
        request.setRequestURI("/error");

        filter.doFilter(request, response, (incoming, outgoing) -> {
            assertThat(MDC.get("traceId")).isEqualTo(traceId);
            assertThat(MDC.get("requestPath")).isEqualTo("/api/songs");
        });

        assertThat(logs.list).hasSize(1);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void consecutiveRequestsGetDifferentIdsAndDoNotInheritUserAgents() throws Exception {
        request.addHeader("User-Agent", "First browser");
        filter.doFilter(request, response, (incoming, outgoing) -> {});
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/songs"), secondResponse,
                (incoming, outgoing) -> assertThat(MDC.get("userAgent")).isEqualTo("unknown"));

        assertThat(secondResponse.getHeader("X-Trace-ID")).isNotEqualTo(response.getHeader("X-Trace-ID"));
        assertThat(secondResponse.getHeader("X-Request-ID")).isNotEqualTo(response.getHeader("X-Request-ID"));
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void suppressesSuccessfulActuatorCompletionAtInfoButStillReportsFailures() throws Exception {
        request.setRequestURI("/actuator/prometheus");
        filter.doFilter(request, response, (incoming, outgoing) -> {});
        assertThat(logs.list).isEmpty();

        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/health"), response,
                (incoming, outgoing) -> response.setStatus(503));

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMDCPropertyMap()).containsEntry("httpStatus", "503");
        });
    }
}
