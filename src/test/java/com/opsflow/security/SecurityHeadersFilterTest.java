package com.opsflow.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SecurityHeadersFilterTest {
    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void addsBrowserSecurityHeadersAndDisablesApiCaching() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tickets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertTrue(response.getHeader("Content-Security-Policy").contains("frame-ancestors 'none'"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Opener-Policy"));
        assertEquals("no-store, max-age=0", response.getHeader("Cache-Control"));
    }
}
