package com.opsflow.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpObservabilityFilter extends OncePerRequestFilter {
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";

    private static final Logger log = LoggerFactory.getLogger(HttpObservabilityFilter.class);
    private static final String UNKNOWN_ROUTE = "UNKNOWN";

    private final MeterRegistry meterRegistry;
    private final ObjectProvider<Tracer> tracerProvider;
    private final Duration slowRequestThreshold;

    public HttpObservabilityFilter(MeterRegistry meterRegistry,
                                   ObjectProvider<Tracer> tracerProvider,
                                   @Value("${opsflow.observability.slow-request-threshold:500ms}")
                                   Duration slowRequestThreshold) {
        this.meterRegistry = meterRegistry;
        this.tracerProvider = tracerProvider;
        this.slowRequestThreshold = slowRequestThreshold;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        exposeTraceContext(response);
        long startedAt = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationNanos = System.nanoTime() - startedAt;
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(durationNanos);
            String route = matchedRoute(request);
            String method = request.getMethod().toUpperCase(Locale.ROOT);
            String status = Integer.toString(response.getStatus());

            if (!response.isCommitted()) {
                response.setHeader("Server-Timing", "app;dur=" + durationMillis);
            }

            if (durationNanos >= slowRequestThreshold.toNanos()) {
                Counter.builder("opsflow.http.slow.requests")
                        .description("Number of API requests slower than the configured threshold")
                        .tag("method", method)
                        .tag("uri", route)
                        .tag("status", status)
                        .register(meterRegistry)
                        .increment();
                log.warn("Slow HTTP request method={} uri={} status={} durationMs={} thresholdMs={} requestId={}",
                        method, route, status, durationMillis, slowRequestThreshold.toMillis(),
                        MDC.get("opsflow.requestId"));
            } else {
                log.info("HTTP request completed method={} uri={} status={} durationMs={} requestId={}",
                        method, route, status, durationMillis, MDC.get("opsflow.requestId"));
            }
        }
    }

    private void exposeTraceContext(HttpServletResponse response) {
        Tracer tracer = tracerProvider.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span == null) return;
        response.setHeader(TRACE_ID_HEADER, span.context().traceId());
        response.setHeader(SPAN_ID_HEADER, span.context().spanId());
    }

    private String matchedRoute(HttpServletRequest request) {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return route == null ? UNKNOWN_ROUTE : route.toString();
    }
}
