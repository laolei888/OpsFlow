package com.opsflow.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginAttemptServiceTest {
    @Mock
    private StringRedisTemplate redisTemplate;

    private OpsFlowSecurityProperties properties;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        properties = new OpsFlowSecurityProperties();
        properties.getLoginProtection().setMaxFailures(5);
        properties.getLoginProtection().setObservationWindow(Duration.ofMinutes(15));
        properties.getLoginProtection().setLockDuration(Duration.ofMinutes(15));
        service = new LoginAttemptService(redisTemplate, properties);
    }

    @Test
    void locksAccountWhenFailureThresholdIsReached() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(5L);

        RateLimitExceededException exception = assertThrows(RateLimitExceededException.class,
                () -> service.recordFailure("AgentOne"));

        assertEquals(900, exception.getRetryAfterSeconds());
    }

    @Test
    void checksExistingLockTtlWithoutExposingUsernameInKey() {
        when(redisTemplate.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(120L);

        RateLimitExceededException exception = assertThrows(RateLimitExceededException.class,
                () -> service.assertAllowed("agent@example.com"));

        assertEquals(120, exception.getRetryAfterSeconds());
    }

    @Test
    void successfulLoginClearsDistributedAndLocalState() {
        assertDoesNotThrow(() -> service.recordSuccess("AgentOne"));
        verify(redisTemplate).delete(any(List.class));
    }

    @Test
    void accountKeyIsCaseAndWhitespaceInsensitive() {
        assertEquals(service.subject(" AgentOne "), service.subject("agentone"));
    }

    @Test
    void localLockRemainsEffectiveAfterRedisRecovers() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        for (int attempt = 1; attempt < 5; attempt++) {
            assertDoesNotThrow(() -> service.recordFailure("AgentOne"));
        }
        assertThrows(RateLimitExceededException.class, () -> service.recordFailure("AgentOne"));

        assertThrows(RateLimitExceededException.class, () -> service.assertAllowed("AgentOne"));
    }
}
