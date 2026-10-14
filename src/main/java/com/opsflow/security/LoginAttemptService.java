package com.opsflow.security;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class LoginAttemptService {
    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            if count >= tonumber(ARGV[2]) then
              redis.call('SET', KEYS[2], '1', 'EX', ARGV[3])
              redis.call('DEL', KEYS[1])
            end
            return count
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final OpsFlowSecurityProperties properties;
    private final ConcurrentHashMap<String, LocalState> fallback = new ConcurrentHashMap<>();

    public LoginAttemptService(StringRedisTemplate redisTemplate, OpsFlowSecurityProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void assertAllowed(String username) {
        String subject = subject(username);
        // A lock created while Redis was unavailable must remain effective even if Redis recovers.
        assertLocalAllowed(subject);
        try {
            Long ttl = redisTemplate.getExpire(lockKey(subject), TimeUnit.SECONDS);
            if (ttl != null && ttl > 0) throw locked(ttl);
        } catch (DataAccessException ex) {
            // The local fallback was checked before attempting Redis.
        }
    }

    public void recordFailure(String username) {
        String subject = subject(username);
        OpsFlowSecurityProperties.LoginProtection config = properties.getLoginProtection();
        long observationSeconds = positiveSeconds(config.getObservationWindow());
        long lockSeconds = positiveSeconds(config.getLockDuration());
        try {
            Long count = redisTemplate.execute(FAILURE_SCRIPT,
                    List.of(failureKey(subject), lockKey(subject)),
                    String.valueOf(observationSeconds), String.valueOf(config.getMaxFailures()),
                    String.valueOf(lockSeconds));
            if (count != null && count >= config.getMaxFailures()) throw locked(lockSeconds);
        } catch (DataAccessException ex) {
            recordLocalFailure(subject, observationSeconds, lockSeconds, config.getMaxFailures());
        }
    }

    public void recordSuccess(String username) {
        String subject = subject(username);
        try {
            redisTemplate.delete(List.of(failureKey(subject), lockKey(subject)));
        } catch (DataAccessException ignored) {
            // Redis is an optimization here; the local fallback is still cleared.
        }
        fallback.remove(subject);
    }

    String subject(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void assertLocalAllowed(String subject) {
        long now = Instant.now().getEpochSecond();
        LocalState state = fallback.get(subject);
        if (state == null) return;
        if (state.lockedUntil > now) throw locked(state.lockedUntil - now);
        if (state.lockedUntil > 0 || state.windowEndsAt <= now) fallback.remove(subject, state);
    }

    private void recordLocalFailure(String subject, long observationSeconds, long lockSeconds, int maxFailures) {
        long now = Instant.now().getEpochSecond();
        LocalState state = fallback.compute(subject, (ignored, current) -> {
            if (current == null || current.windowEndsAt <= now) {
                current = new LocalState(0, now + observationSeconds, 0);
            }
            if (current.lockedUntil > now) return current;
            int failures = current.failures + 1;
            long lockedUntil = failures >= maxFailures ? now + lockSeconds : 0;
            return new LocalState(failures, current.windowEndsAt, lockedUntil);
        });
        if (state.lockedUntil > now) throw locked(state.lockedUntil - now);
        if (fallback.size() > 10_000) {
            fallback.entrySet().removeIf(entry -> entry.getValue().windowEndsAt <= now
                    && entry.getValue().lockedUntil <= now);
        }
    }

    private RateLimitExceededException locked(long retryAfterSeconds) {
        return new RateLimitExceededException("登录失败次数过多，请稍后再试", retryAfterSeconds);
    }

    private long positiveSeconds(java.time.Duration duration) {
        return Math.max(1, duration == null ? 1 : duration.toSeconds());
    }

    private String failureKey(String subject) {
        return "opsflow:auth:{" + subject + "}:failures";
    }

    private String lockKey(String subject) {
        return "opsflow:auth:{" + subject + "}:lock";
    }

    private record LocalState(int failures, long windowEndsAt, long lockedUntil) { }
}
