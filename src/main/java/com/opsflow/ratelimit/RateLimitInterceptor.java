package com.opsflow.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final long LIMIT = 30;
    private static final long WINDOW_SECONDS = 60;
    private final StringRedisTemplate redisTemplate;
    private final ConcurrentHashMap<String, LocalWindow> fallback = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !isProtectedWrite(request.getRequestURI())) return true;
        String clientKey = request.getRemoteAddr() + ":" + request.getRequestURI();
        if (!allow(clientKey)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"success\":false,\"data\":null,\"message\":\"请求过于频繁，请稍后重试\"}");
            return false;
        }
        return true;
    }

    private boolean isProtectedWrite(String uri) {
        return uri.equals("/api/v1/tickets") || uri.matches("/api/v1/tickets/\\d+/transition")
                || uri.equals("/api/v1/knowledge/articles") || uri.matches("/api/v1/knowledge/articles/\\d+/publish");
    }

    private boolean allow(String key) {
        try {
            String redisKey = "opsflow:rate:" + key;
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1L) redisTemplate.expire(redisKey, java.time.Duration.ofSeconds(WINDOW_SECONDS));
            return count == null || count <= LIMIT;
        } catch (RuntimeException ignored) {
            return allowLocal(key);
        }
    }

    private boolean allowLocal(String key) {
        long now = Instant.now().getEpochSecond();
        LocalWindow window = fallback.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt >= WINDOW_SECONDS) return new LocalWindow(now, 1);
            return new LocalWindow(current.startedAt, current.count + 1);
        });
        return window.count <= LIMIT;
    }

    private record LocalWindow(long startedAt, long count) { }
}
