package com.radioparadise.tracker.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final String CONTEXT_ATTRIBUTE = RequestLoggingFilter.class.getName() + ".context";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern TRACE_PARENT =
            Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}");

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        RequestContext context = requestContext(request);
        boolean failed = false;
        try {
            context.fields().forEach(MDC::put);
            response.setHeader("X-Trace-ID", context.fields().get("traceId"));
            response.setHeader("X-Request-ID", context.fields().get("requestId"));
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            failed = true;
            throw exception;
        } finally {
            try {
                // The initial dispatch already records an escaping failure as 500.
                if (request.getDispatcherType() != DispatcherType.ERROR) {
                    logCompletion(context, failed ? 500 : response.getStatus());
                }
            } finally {
                restoreContext(previousContext);
            }
        }
    }

    private RequestContext requestContext(HttpServletRequest request) {
        if (request.getAttribute(CONTEXT_ATTRIBUTE) instanceof RequestContext context) {
            return context;
        }
        String traceId = traceId(request.getHeader("traceparent"));
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            requestId = newId();
        }
        RequestContext context = new RequestContext(Map.of(
                "traceId", traceId,
                "requestId", requestId,
                "clientIp", safeLogValue(request.getRemoteAddr(), 64),
                "userAgent", safeLogValue(request.getHeader("User-Agent"), 512),
                "httpMethod", safeLogValue(request.getMethod(), 32),
                "requestPath", safeLogValue(request.getRequestURI(), 2048)), System.nanoTime());
        request.setAttribute(CONTEXT_ATTRIBUTE, context);
        return context;
    }

    private String traceId(String traceParent) {
        if (traceParent == null) {
            return newId();
        }
        var matcher = TRACE_PARENT.matcher(traceParent);
        if (!matcher.matches()) {
            return newId();
        }
        boolean hasValidIds = !matcher.group(1).equals("0".repeat(32))
                && !matcher.group(2).equals("0".repeat(16));
        return hasValidIds ? matcher.group(1) : newId();
    }

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String safeLogValue(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < Math.min(value.length(), maxLength); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || character == '\u2028' || character == '\u2029') {
                sanitized.append(' ');
                continue;
            }
            if (character == '"' || character == '\\') {
                sanitized.append('\\');
            }
            sanitized.append(character);
        }
        return sanitized.toString();
    }

    private void logCompletion(RequestContext context, int status) {
        MDC.put("httpStatus", Integer.toString(status));
        MDC.put("durationMs", Long.toString(
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - context.startedAtNanos())));
        if (status >= 500) {
            log.warn("HTTP request completed");
        } else if (context.fields().get("requestPath").startsWith("/actuator/")) {
            log.debug("HTTP request completed");
        } else {
            log.info("HTTP request completed");
        }
    }

    private void restoreContext(Map<String, String> previousContext) {
        if (previousContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previousContext);
        }
    }

    private record RequestContext(Map<String, String> fields, long startedAtNanos) {}
}
