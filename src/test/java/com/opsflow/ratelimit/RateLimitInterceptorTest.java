package com.opsflow.ratelimit;

import com.opsflow.security.OpsFlowSecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void throttlesAnonymousLoginByRemoteAddress() throws Exception {
        OpsFlowSecurityProperties properties = new OpsFlowSecurityProperties();
        properties.getRateLimit().setLoginAttempts(10);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate, properties);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(11L);
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(30L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("198.51.100.20");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(429, response.getStatus());
        assertEquals("10", response.getHeader("X-RateLimit-Limit"));
        assertEquals("0", response.getHeader("X-RateLimit-Remaining"));
        assertTrue(response.getContentAsString().contains("请求过于频繁"));
    }

    @Test
    void leavesReadRequestsOutsideWriteLimiter() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate,
                new OpsFlowSecurityProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tickets");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
