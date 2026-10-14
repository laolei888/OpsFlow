package com.opsflow.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HttpObservabilityFilterTest {
    @Test
    void recordsSlowRequestsWithTheMatchedRouteInsteadOfTheRawUrl() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(null);
        HttpObservabilityFilter filter = new HttpObservabilityFilter(
                registry, tracerProvider, Duration.ZERO);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tickets/42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/tickets/{id}");

        filter.doFilter(request, response, chain);

        assertThat(registry.get("opsflow.http.slow.requests")
                .tags("method", "GET", "uri", "/api/v1/tickets/{id}", "status", "200")
                .counter().count()).isEqualTo(1.0);
        assertThat(response.getHeader("Server-Timing")).startsWith("app;dur=");
    }

    @Test
    void ignoresStaticAndActuatorRequests() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        HttpObservabilityFilter filter = new HttpObservabilityFilter(
                registry, tracerProvider, Duration.ZERO);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");

        filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> { });

        assertThat(registry.find("opsflow.http.slow.requests").counter()).isNull();
    }
}
