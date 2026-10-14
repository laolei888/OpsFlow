package com.opsflow.ratelimit;

import com.opsflow.auth.AuthPrincipal;
import com.opsflow.security.OpsFlowSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final OpsFlowSecurityProperties properties;
    private final ConcurrentHashMap<String, LocalWindow> fallback = new ConcurrentHashMap<>();

    public RateLimitInterceptor(StringRedisTemplate redisTemplate, OpsFlowSecurityProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        LimitRule rule = ruleFor(request);
        if (rule == null) return true;

        String clientKey = "opsflow:rate:" + rule.name + ":" + actorKey(request);
        Decision decision = allow(clientKey, rule.limit, rule.window);
        response.setHeader("X-RateLimit-Limit", String.valueOf(rule.limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, rule.limit - decision.count)));
        response.setHeader("X-RateLimit-Reset", String.valueOf(decision.resetAt));
        if (decision.count <= rule.limit) return true;

        long retryAfter = Math.max(1, decision.resetAt - Instant.now().getEpochSecond());
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"success\":false,\"data\":null,\"message\":\"请求过于频繁，请稍后重试\",\"requestId\":\""
                + safeJson(MDC.get("opsflow.requestId")) + "\"}");
        return false;
    }

    private LimitRule ruleFor(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        OpsFlowSecurityProperties.RateLimit config = properties.getRateLimit();
        if ("POST".equalsIgnoreCase(method) && uri.equals("/api/v1/auth/login")) {
            return new LimitRule("login", config.getLoginAttempts(), config.getLoginWindow());
        }
        if ("POST".equalsIgnoreCase(method) && uri.equals("/api/v1/auth/register")) {
            return new LimitRule("register", config.getRegistrationAttempts(), config.getRegistrationWindow());
        }
        if ("POST".equalsIgnoreCase(method) && uri.startsWith("/api/v1/alert-ingest/")) {
            return new LimitRule("alert-ingest", config.getAlertIngestRequests(), config.getAlertIngestWindow());
        }
        if (uri.startsWith("/api/v1/") && isWriteMethod(method)) {
            return new LimitRule("write", config.getWriteRequests(), config.getWriteWindow());
        }
        return null;
    }

    private boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private String actorKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthPrincipal principal) {
            return "user:" + principal.id();
        }
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) address = "unknown";
        return "ip:" + address.replaceAll("[^0-9A-Fa-f:._-]", "_");
    }

    private Decision allow(String key, long limit, Duration window) {
        long windowSeconds = Math.max(1, window == null ? 1 : window.toSeconds());
        long resetAt = Instant.now().getEpochSecond() + windowSeconds;
        try {
            Long count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(windowSeconds));
            Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) resetAt = Instant.now().getEpochSecond() + ttl;
            return new Decision(count == null ? 1 : count, resetAt);
        } catch (DataAccessException ignored) {
            return allowLocal(key, windowSeconds);
        }
    }

    private Decision allowLocal(String key, long windowSeconds) {
        long now = Instant.now().getEpochSecond();
        LocalWindow window = fallback.compute(key, (ignored, current) -> {
            if (current == null || current.resetAt <= now) return new LocalWindow(now + windowSeconds, 1);
            return new LocalWindow(current.resetAt, current.count + 1);
        });
        if (fallback.size() > 10_000) fallback.entrySet().removeIf(entry -> entry.getValue().resetAt <= now);
        return new Decision(window.count, window.resetAt);
    }

    private String safeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record LimitRule(String name, long limit, Duration window) { }
    private record Decision(long count, long resetAt) { }
    private record LocalWindow(long resetAt, long count) { }
}
